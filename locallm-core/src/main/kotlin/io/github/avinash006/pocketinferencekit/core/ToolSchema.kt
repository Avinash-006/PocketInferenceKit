package io.github.avinash006.pocketinferencekit.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ToolSchema private constructor(
    val type: String,
    private val properties: Map<String, ToolSchema>,
    private val required: Set<String>,
    private val allowAdditional: Boolean,
    private val items: ToolSchema?,
    private val choices: List<JsonElement>?,
) {
    fun validate(
        value: JsonElement,
        location: String,
    ): String? {
        val validType =
            when (type) {
                "object" -> {
                    value is JsonObject
                }

                "array" -> {
                    value is JsonArray
                }

                "string" -> {
                    value is JsonPrimitive && value.isString
                }

                "boolean" -> {
                    value is JsonPrimitive && !value.isString && value.content in BOOLEANS
                }

                "null" -> {
                    value === JsonNull
                }

                "number" -> {
                    value.number() != null
                }

                "integer" -> {
                    value
                        .number()
                        ?.stripTrailingZeros()
                        ?.scale()
                        ?.let { it <= 0 } == true
                }

                else -> {
                    false
                }
            }
        if (!validType) return "$location must have JSON type $type."
        if (choices != null && value !in choices) return "$location must match an enumerated value."
        return when {
            value is JsonObject -> {
                validateObject(value, location)
            }

            value is JsonArray && items != null -> {
                value.withIndex().firstNotNullOfOrNull { (index, child) ->
                    items.validate(child, "$location[$index]")
                }
            }

            else -> {
                null
            }
        }
    }

    private fun validateObject(
        value: JsonObject,
        location: String,
    ): String? {
        if (!value.keys.containsAll(required)) return "$location is missing a required property."
        for ((name, child) in value) {
            val schema = properties[name]
            if (schema == null && !allowAdditional) return "$location contains an unexpected property."
            schema?.validate(child, "$location.$name")?.let { return it }
        }
        return null
    }

    companion object {
        private val TYPES = setOf("object", "array", "string", "boolean", "null", "number", "integer")
        private val BOOLEANS = setOf("true", "false")
        private val COMMON = setOf("type", "description", "title", "enum")

        fun compile(
            schema: JsonObject,
            depth: Int = 0,
        ): ToolSchema {
            require(depth <= 12) { "Tool schemas can have at most 12 nested levels." }
            val type = schema["type"].string()
            require(type != null && type in TYPES) { "Each tool schema must declare a supported type." }
            val keys =
                when (type) {
                    "object" -> COMMON + setOf("properties", "required", "additionalProperties")
                    "array" -> COMMON + "items"
                    else -> COMMON
                }
            require(schema.keys.all { it in keys }) { "The tool schema contains an unsupported keyword." }
            val properties =
                if ("properties" in schema) {
                    val definition = requireNotNull(schema["properties"] as? JsonObject) { "properties must be an object." }
                    definition.mapValues { (_, value) ->
                        compile(requireNotNull(value as? JsonObject) { "Property schemas must be objects." }, depth + 1)
                    }
                } else {
                    emptyMap()
                }
            val required =
                if ("required" in schema) {
                    val list = requireNotNull(schema["required"] as? JsonArray) { "required must be an array." }
                    list.map { requireNotNull(it.string()) { "required entries must be names." } }.toSet()
                } else {
                    emptySet()
                }
            require(properties.keys.containsAll(required)) { "Required properties must be defined in properties." }
            val additional =
                schema["additionalProperties"]?.let {
                    require(it is JsonPrimitive && !it.isString && it.content in BOOLEANS) {
                        "additionalProperties must be a JSON boolean."
                    }
                    it.content == "true"
                } ?: true
            val itemSchema =
                schema["items"]?.let {
                    compile(requireNotNull(it as? JsonObject) { "items must be a schema object." }, depth + 1)
                }
            val choices =
                schema["enum"]?.let {
                    require(it is JsonArray && it.isNotEmpty()) { "enum must be a nonempty array." }
                    it.toList()
                }
            return ToolSchema(type, properties, required, additional, itemSchema, choices)
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

        private fun JsonElement.number(): java.math.BigDecimal? = (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toBigDecimalOrNull()
    }
}
