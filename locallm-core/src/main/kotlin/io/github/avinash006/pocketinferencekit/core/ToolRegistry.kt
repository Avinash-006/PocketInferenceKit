package io.github.avinash006.pocketinferencekit.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Immutable registry of tools that a compatible model may request. Registered handlers are responsible
 * for coordinating shared state if the registry is used by multiple engines.
 */
public class ToolRegistry private constructor(
    internal val tools: Map<String, RegisteredTool>,
    internal val maxRounds: Int,
) {
    /** Builds a registry of named tools. Builders are single-use during the DSL call. */
    public class Builder internal constructor() {
        private val tools = linkedMapOf<String, RegisteredTool>()

        /**
         * Registers a unique [name], its model-facing [description], and a JSON object parameter [schema].
         * The library validates arguments before invoking [execute].
         */
        public fun register(
            name: String,
            description: String,
            schema: JsonObject,
            execute: suspend (JsonObject) -> JsonElement,
        ) {
            require(TOOL_NAME.matches(name)) { "Tool names must be 1–64 characters and use letters, digits, or underscores." }
            require(description.isNotBlank() && description.length <= 1_024)
            require(name !in tools) { "A tool named '$name' is already registered." }
            schema.checkToolJsonBounds()
            val specification = Json.parseToJsonElement(schema.toString()) as JsonObject
            val compiled = ToolSchema.compile(specification)
            require(compiled.type == "object") { "A tool parameter schema must have type object." }
            tools[name] = RegisteredTool(
                name,
                buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("parameters", specification)
                },
                compiled,
                execute,
            )
        }

        internal fun build(maxRounds: Int): ToolRegistry = ToolRegistry(tools.toMap(), maxRounds)

        private companion object {
            val TOOL_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        }
    }

    /** Factory methods for tool registries. */
    public companion object {
        /** Disables tool registration and execution. */
        public val Empty: ToolRegistry = ToolRegistry(emptyMap(), 0)

        /** Creates a registry with up to [maxRounds] tool-response rounds per user turn. */
        public fun build(maxRounds: Int = 4, configure: Builder.() -> Unit): ToolRegistry {
            require(maxRounds in 1..16) { "The tool round limit must be between 1 and 16." }
            return Builder().apply(configure).build(maxRounds)
        }
    }
}

internal data class RegisteredTool(
    val name: String,
    val specification: JsonObject,
    val schema: ToolSchema,
    val execute: suspend (JsonObject) -> JsonElement,
)

internal data class StructuredToolCall(val name: String, val argumentsJson: String)

internal fun ToolRegistry.prepare(call: StructuredToolCall): Pair<RegisteredTool, JsonObject> {
    val registration = tools[call.name]
        ?: throw LocalLMException.ToolExecutionFailed("The model requested an unregistered tool.")
    return registration to parseToolArguments(call.argumentsJson, registration.schema)
}

internal fun parseToolArguments(raw: String, schema: ToolSchema): JsonObject {
    if (raw.length > MAX_TOOL_JSON_CHARS) {
        throw LocalLMException.ToolExecutionFailed("The model returned too many tool argument characters.")
    }
    val parsed = try {
        Json.parseToJsonElement(raw)
    } catch (error: IllegalArgumentException) {
        throw LocalLMException.ToolExecutionFailed("The model returned malformed or incomplete tool arguments.", error)
    }
    parsed.checkToolJsonBounds()
    val objectValue = parsed as? JsonObject
        ?: throw LocalLMException.ToolExecutionFailed("Tool arguments must be a JSON object.")
    schema.validate(objectValue, "arguments")?.let { throw LocalLMException.ToolExecutionFailed(it) }
    return objectValue
}

internal const val MAX_TOOL_JSON_CHARS = 65_536

internal fun JsonElement.toNativeValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { (_, value) -> value.toNativeValue() }
    is JsonArray -> map { it.toNativeValue() }
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toBigDecimal()
    }
}

internal fun JsonElement.checkToolJsonBounds() {
    fun check(node: JsonElement, depth: Int) {
        if (depth > 32) {
            throw LocalLMException.ToolExecutionFailed("Tool JSON is nested too deeply.")
        }
        when (node) {
            is JsonArray -> node.forEach { check(it, depth + 1) }
            is JsonObject -> node.values.forEach { check(it, depth + 1) }
            is JsonPrimitive -> Unit
        }
    }
    check(this, 0)
    if (toString().length > MAX_TOOL_JSON_CHARS) {
        throw LocalLMException.ToolExecutionFailed("Tool JSON is too large.")
    }
}

internal fun nativeArguments(arguments: Map<String, Any?>): String {
    fun encode(value: Any?, depth: Int): JsonElement {
        if (depth > 32) throw LocalLMException.ToolExecutionFailed("Tool arguments are nested too deeply.")
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> {
                if (value.toString().toBigDecimalOrNull() == null) {
                    throw LocalLMException.ToolExecutionFailed("Tool arguments contain an invalid number.")
                }
                JsonPrimitive(value)
            }
            is List<*> -> JsonArray(value.map { encode(it, depth + 1) })
            is Map<*, *> -> JsonObject(value.entries.associate { (key, child) ->
                val name = key as? String
                    ?: throw LocalLMException.ToolExecutionFailed("Tool argument keys must be strings.")
                name to encode(child, depth + 1)
            })
            else -> throw LocalLMException.ToolExecutionFailed("Unsupported tool argument value.")
        }
    }
    return encode(arguments, 0).also { it.checkToolJsonBounds() }.toString()
}
