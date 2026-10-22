package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DeezerLoginScreen(
    existingToken: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    var arlToken by remember { mutableStateOf(existingToken) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(32.dp))
        
        Text(
            "Deezer Integration",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            "Full-track streaming requires your Deezer ARL token.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            "How to get your ARL token:",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(Modifier.height(12.dp))
        
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "1. Open deezer.com in a desktop browser\n" +
                "2. Press F12 to open DevTools\n" +
                "3. Go to the Application/Storage tab\n" +
                "4. Find Cookies → https://www.deezer.com\n" +
                "5. Locate the 'arl' cookie and copy its value\n" +
                "6. Paste it below",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(Modifier.height(24.dp))
        
        OutlinedTextField(
            value = arlToken,
            onValueChange = { arlToken = it.trim() },
            label = { Text("ARL Token") },
            placeholder = { Text("Paste your ARL token here...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3
        )
        
        Spacer(Modifier.height(16.dp))
        
        Button(
            onClick = { onSave(arlToken) },
            enabled = arlToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Token")
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (existingToken.isNotBlank()) {
            TextButton(
                onClick = { onSave("") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Remove Token")
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        TextButton(onClick = onBack) {
            Text("Cancel")
        }
        
        Spacer(Modifier.height(32.dp))
    }
}
