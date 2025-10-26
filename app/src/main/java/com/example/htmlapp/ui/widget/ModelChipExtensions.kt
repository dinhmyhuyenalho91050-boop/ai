package com.example.htmlapp.ui.widget

import androidx.appcompat.view.ContextThemeWrapper
import com.example.htmlapp.R
import com.example.htmlapp.ui.ModelPresetUi
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

fun ChipGroup.updateModelChips(
    models: List<ModelPresetUi>,
    selectedModelId: String,
    onSelect: (String) -> Unit,
) {
    removeAllViews()
    val themedContext = ContextThemeWrapper(context, R.style.ModelChip)
    models.forEach { model ->
        val chip = Chip(themedContext).apply {
            text = model.displayName
            isCheckable = true
            isChecked = model.id == selectedModelId
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    onSelect(model.id)
                }
            }
        }
        addView(chip)
    }
}
