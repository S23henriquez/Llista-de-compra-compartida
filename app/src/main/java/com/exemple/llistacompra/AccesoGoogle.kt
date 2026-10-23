package com.exemple.llistacompra

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

/**
 * Acceso con Google, compartido por LoginActivity y RegisterActivity.
 *
 * Con Google, "iniciar sesión" y "registrarse" son lo mismo:
 * si la cuenta no existe en Firebase, se crea automáticamente.
 *
 *  1. Abre el selector de cuentas de Google (Credential Manager).
 *  2. Recibe un "ID token" de la cuenta elegida.
 *  3. Se lo pasa a Firebase, que inicia sesión (o crea la cuenta).
 *
 * @param onCargando se llama con true al empezar y false al terminar (para desactivar botones)
 * @param onExito    se llama cuando ya hay sesión iniciada
 */
class AccesoGoogle(
    private val activity: AppCompatActivity,
    private val onCargando: (Boolean) -> Unit,
    private val onExito: () -> Unit
) {

    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(activity)

    fun iniciar() {
        val webClientId = obtenerWebClientId()
        if (webClientId == null) {
            toast("Google todavía no está configurado en Firebase")
            return
        }

        val opcionGoogle = GetSignInWithGoogleOption.Builder(webClientId).build()
        val peticion = GetCredentialRequest.Builder()
            .addCredentialOption(opcionGoogle)
            .build()

        onCargando(true)

        activity.lifecycleScope.launch {
            try {
                val resultado = credentialManager.getCredential(activity, peticion)
                val credencial = resultado.credential

                if (credencial is CustomCredential &&
                    credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val cuentaGoogle = GoogleIdTokenCredential.createFrom(credencial.data)
                    entrarEnFirebase(cuentaGoogle.idToken)
                } else {
                    onCargando(false)
                    toast("Tipo de cuenta no admitido")
                }
            } catch (e: GetCredentialCancellationException) {
                // El usuario ha cerrado el selector: no mostramos error
                onCargando(false)
            } catch (e: NoCredentialException) {
                onCargando(false)
                toast("No hay ninguna cuenta de Google en este dispositivo")
            } catch (e: GoogleIdTokenParsingException) {
                onCargando(false)
                Log.e(TAG, "Token de Google no válido", e)
                toast("No se ha podido leer la cuenta de Google")
            } catch (e: GetCredentialException) {
                onCargando(false)
                Log.e(TAG, "Error de Credential Manager", e)
                toast("No se ha podido continuar con Google")
            }
        }
    }

    private fun entrarEnFirebase(idToken: String) {
        val credencialFirebase = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(credencialFirebase)
            .addOnCompleteListener(activity) { task ->
                onCargando(false)
                if (task.isSuccessful) {
                    onExito()
                } else {
                    Log.e(TAG, "Firebase rechazó la cuenta de Google", task.exception)
                    toast(
                        when (task.exception) {
                            is FirebaseAuthUserCollisionException ->
                                "Ese correo ya está registrado con contraseña. Entra con tu correo"
                            is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
                            else -> "No se ha podido continuar con Google"
                        }
                    )
                }
            }
    }

    /**
     * El "Web client ID" lo genera el plugin de google-services
     * (R.string.default_web_client_id) cuando Google está activado en Firebase.
     * Lo buscamos así para que la app compile aunque todavía no esté configurado.
     */
    private fun obtenerWebClientId(): String? {
        val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        return if (id != 0) activity.getString(id) else null
    }

    private fun toast(mensaje: String) =
        Toast.makeText(activity, mensaje, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "AccesoGoogle"
    }
}
