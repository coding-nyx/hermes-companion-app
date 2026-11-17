package app.hermes.hands

import org.json.JSONObject

/** Explicit selectors are assertions, never a request to change local configuration. */
internal fun armSelectionMismatch(args: JSONObject, target: String, backend: String): JSONObject? {
    if (args.has("target") && args.getString("target") != target) {
        return error("target_mismatch", "Hands has target=$target selected. Omit target to use it, or select the intended screen in Hands.")
    }
    if (args.has("backend") && args.getString("backend") != backend) {
        return error("backend_mismatch", "Hands has backend=$backend selected. Omit backend to use it, or select the intended backend in Hands.")
    }
    return null
}
