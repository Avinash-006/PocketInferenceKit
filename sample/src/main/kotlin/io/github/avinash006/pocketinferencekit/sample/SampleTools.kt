package io.github.avinash006.pocketinferencekit.sample

import io.github.avinash006.pocketinferencekit.core.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

internal val sampleTools: ToolRegistry =
    ToolRegistry.build {
        register(
            "get_time",
            "Read the current clock time in UTC.",
            schema("""{"type":"object","additionalProperties":false}"""),
        ) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            format.timeZone = TimeZone.getTimeZone("UTC")
            JsonPrimitive(format.format(Date()))
        }

        register(
            "calculator",
            "Perform a basic arithmetic operation on two numbers.",
            schema(
                """
                {
                  "type":"object",
                  "properties":{
                    "operation":{"type":"string","enum":["add","subtract","multiply","divide"]},
                    "left":{"type":"number"},
                    "right":{"type":"number"}
                  },
                  "required":["operation","left","right"],
                  "additionalProperties":false
                }
                """.trimIndent(),
            ),
        ) { arguments ->
            val left = arguments.getValue("left").jsonPrimitive.double
            val right = arguments.getValue("right").jsonPrimitive.double
            require(abs(left) <= 1e12 && abs(right) <= 1e12) { "Inputs must have magnitude at most 1 trillion." }
            val answer =
                when (arguments.getValue("operation").jsonPrimitive.content) {
                    "add" -> {
                        left + right
                    }

                    "subtract" -> {
                        left - right
                    }

                    "multiply" -> {
                        left * right
                    }

                    "divide" -> {
                        require(right != 0.0) { "Cannot divide by zero." }
                        left / right
                    }

                    else -> {
                        error("Unknown operation.")
                    }
                }
            require(answer.isFinite()) { "Result is outside the calculator's range." }
            JsonPrimitive(answer)
        }
    }

private fun schema(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
