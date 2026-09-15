package fr.hermesmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import fr.hermesmusic.core.kicker
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(graph: AppGraph) {
    var serverUrl by remember { mutableStateOf("http://100.64.0.0:8096") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("HERMES MUSIC", style = kicker(), color = Nocturne.Accent)
        Spacer(Modifier.height(10.dp))
        Text(
            "Se connecter",
            color = Nocturne.Ink,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(28.dp))

        field("Serveur Jellyfin", serverUrl, KeyboardType.Uri) { serverUrl = it }
        Spacer(Modifier.height(14.dp))
        field("Utilisateur", username, KeyboardType.Text) { username = it }
        Spacer(Modifier.height(14.dp))
        field("Mot de passe", password, KeyboardType.Password, secret = true) { password = it }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = {
                busy = true
                message = null
                scope.launch {
                    graph.connect(serverUrl, username, password).fold(
                        onSuccess = { message = it; isError = false },
                        onFailure = { message = it.message ?: "Connexion impossible"; isError = true },
                    )
                    busy = false
                }
            },
            enabled = !busy && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Nocturne.Accent, contentColor = Color(0xFF0E0F18)),
        ) {
            if (busy) {
                CircularProgressIndicator(color = Color(0xFF0E0F18), strokeWidth = 2.dp)
            } else {
                Text("Se connecter", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        message?.let {
            Spacer(Modifier.height(18.dp))
            Text(
                it,
                color = if (isError) Color(0xFFFF8A8A) else Nocturne.Dim,
                fontSize = 13.sp,
                fontFamily = FontFamily.SansSerif,
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Le mot de passe n'est pas conservé : seul un jeton d'accès est enregistré sur l'appareil.",
            color = Nocturne.Dim2,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    type: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = kicker())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Nocturne.Surface2,
                unfocusedContainerColor = Nocturne.Surface2,
                focusedBorderColor = Nocturne.Accent,
                unfocusedBorderColor = Color(0x24E9E9ED),
                focusedTextColor = Nocturne.Ink,
                unfocusedTextColor = Nocturne.Ink,
                cursorColor = Nocturne.Accent,
            ),
        )
    }
}
