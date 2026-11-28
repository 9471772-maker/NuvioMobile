package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import com.nuvio.app.features.addons.AddonsSettingsPageContent
import com.nuvio.app.features.addons.InstalledAddonsPageContent

internal fun LazyListScope.addonsSettingsContent() {
    item {
        AddonsSettingsPageContent(
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun LazyListScope.installedAddonsSettingsContent() {
    item { InstalledAddonsPageContent(modifier = Modifier.fillMaxWidth()) }
}
