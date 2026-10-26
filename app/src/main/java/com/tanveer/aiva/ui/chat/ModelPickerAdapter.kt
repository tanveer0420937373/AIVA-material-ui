package com.tanveer.aiva.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tanveer.aiva.R
import com.tanveer.aiva.domain.models.AiModel
import com.tanveer.aiva.domain.models.Capability
import com.tanveer.aiva.domain.models.ProviderId

/** RecyclerView-backed model list so hundreds of provider models do not inflate hundreds of rows at once. */
class ModelPickerAdapter(
    private val onModelSelected: (AiModel) -> Unit,
) : RecyclerView.Adapter<ModelPickerAdapter.ModelViewHolder>() {
    private var items: List<AiModel> = emptyList()
    private var selectedModelId: String = ""
    private var configuredProviders: Set<ProviderId> = emptySet()

    fun submitModels(models: List<AiModel>, selectedId: String?, configured: Set<ProviderId>) {
        items = models
        selectedModelId = selectedId.orEmpty()
        configuredProviders = configured
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder = ModelViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false),
    )

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        val model = items[position]
        val selected = model.id == selectedModelId
        val needsKey = when {
            model.unavailable || model.provider == ProviderId.HuggingFace -> true
            Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities -> ProviderId.Gemini !in configuredProviders
            else -> model.provider.needsKey && model.provider !in configuredProviders
        }
        with(holder) {
            mark.text = model.mark
            name.text = model.name
            vendor.text = if (Capability.ImageGeneration in model.capabilities && Capability.Text !in model.capabilities) {
                "AIVA profile · image generation (not a provider catalog model)"
            } else {
                "${model.vendor} · via ${model.provider.displayName}"
            }
            remoteId.text = model.remoteId
            description.text = model.description
            capabilities.text = model.capabilities.joinToString("  ·  ") { it.label }
            status.visibility = if (selected || needsKey) View.VISIBLE else View.GONE
            status.text = when {
                selected && needsKey -> "Selected · needs API key"
                selected -> "✓  Selected"
                needsKey -> if (model.provider == ProviderId.HuggingFace) "Provider temporarily on hold" else "Needs API key in Settings"
                else -> ""
            }
            itemView.alpha = 1f
            itemView.setOnClickListener { onModelSelected(model) }
        }
    }

    class ModelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mark: TextView = view.findViewById(R.id.model_mark)
        val name: TextView = view.findViewById(R.id.model_name)
        val vendor: TextView = view.findViewById(R.id.model_vendor)
        val remoteId: TextView = view.findViewById(R.id.model_remote_id)
        val description: TextView = view.findViewById(R.id.model_description)
        val capabilities: TextView = view.findViewById(R.id.model_capabilities)
        val status: TextView = view.findViewById(R.id.model_status)
    }
}
