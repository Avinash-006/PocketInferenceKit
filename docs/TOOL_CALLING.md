# Tool calling

Register trusted Kotlin handlers and give each one a JSON Schema for its input. Pass the registry to `newChat`. Handlers receive validated JSON objects and return JSON values.

```kotlin
import io.github.avinash006.pocketinferencekit.core.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val noArguments = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
}
val tools = ToolRegistry.build(maxRounds = 4) {
    register("app_version", "Read the version of this app.", noArguments) {
        JsonPrimitive(BuildConfig.VERSION_NAME)
    }
}
val chat = engine.newChat(tools = tools)
```

Collect `chat.send(prompt)` to observe `ToolCall` events. A tool event contains the validated name and arguments. The library invokes the handler, returns its value to the same native conversation, and continues streaming. `sendText` yields only visible text.

The SDK validates the whole batch before executing any handler. It rejects unknown names, malformed or partial JSON, non-object arguments, extra properties forbidden by the schema, and excessive tool rounds. Supported schema keywords are `type`, `properties`, `required`, `additionalProperties` as a boolean, `items`, `enum`, `title`, and `description`. The SDK caps argument and result lengths and nesting depth.

Only structured calls emitted by LiteRT-LM can trigger a handler. Models that do not declare function calling support can still respond with ordinary chat text. The catalog has publisher-verified file metadata. Tool-call reliability by model and backend still needs Android device measurements, so use a model that declares function calling and verify it with your own prompts. The sample logs actual clock and calculator requests when a model makes them.

Google's pinned Kotlin guide identifies FunctionGemma as a tool-capable model. Its publisher gates access under the Gemma license, so it is an import candidate after the user obtains a compatible bundle. It is outside the automatic-download catalog. [Upstream Kotlin guide](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/docs/api/kotlin/getting_started.md) · [FunctionGemma model card](https://huggingface.co/google/functiongemma-270m-it)

Handlers run on the inference worker dispatcher. Use cancellable operations and only register actions that the app intends the model to request. A handler should avoid waiting on another chat from the same engine while its current turn is active.
