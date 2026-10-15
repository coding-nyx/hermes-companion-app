package app.hermes.companion.data.remote

import app.hermes.companion.model.ModelCatalog
import app.hermes.companion.model.ModelOption
import kotlinx.serialization.json.*

/** Shared wire shape; the session picker uses its captured session's inventory. */
internal fun parseModelCatalog(obj: JsonObject): ModelCatalog {
    val currentModel = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val currentProvider = obj["provider"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val providers = obj["providers"]?.jsonArray ?: emptyList()
    val options = mutableListOf<ModelOption>()
    for (p in providers) {
        val pObj = p.jsonObject
        val pSlug = pObj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val pName = pObj["name"]?.jsonPrimitive?.contentOrNull ?: pSlug
        val models = pObj["models"]?.jsonArray ?: emptyList()
        val caps = pObj["capabilities"]?.jsonObject
        for (m in models) {
            val mName = m.jsonPrimitive.contentOrNull.orEmpty()
            val mCaps = caps?.get(mName)?.jsonObject
            val reasoning = mCaps?.get("reasoning")?.jsonPrimitive?.booleanOrNull ?: false
            val fast = mCaps?.get("fast")?.jsonPrimitive?.booleanOrNull ?: false
            options.add(ModelOption(id = mName, provider = pSlug, name = "$pName · $mName", reasoning = reasoning, fast = fast,
                    canDisableReasoning = mCaps?.get("can_disable_reasoning")?.jsonPrimitive?.booleanOrNull))
        }
    }
    return ModelCatalog(currentModel = currentModel, currentProvider = currentProvider, models = options.distinctBy { it.provider to it.id })
}
