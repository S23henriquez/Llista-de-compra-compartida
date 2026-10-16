package com.exemple.llistacompra

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    private lateinit var btnLogin: Button
    private lateinit var btnGoogle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // Si ya hay una sesión iniciada, vamos directos a la lista
        if (auth.currentUser != null) {
            irAMain()
            return
        }

        setContentView(R.layout.login)
        configurarBarrasSistema(findViewById(R.id.cabecera))

        credentialManager = CredentialManager.create(this)

        val inputEmail = findViewById<TextInputEditText>(R.id.inputEmail)
        val inputPassword = findViewById<TextInputEditText>(R.id.inputPassword)
        val btnGoToRegister = findViewById<Button>(R.id.btnGoToRegister)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogle)

        // ── Correo y contraseña ─────────────────────────────
        btnLogin.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            // La contraseña NO se recorta: los espacios pueden formar parte de ella
            val password = inputPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                toast("Por favor, completa todos los campos")
                return@setOnClickListener
            }

            cargando(true)

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    cargando(false)
                    if (task.isSuccessful) {
                        irAMain()
                    } else {
                        toast(mensajeDeError(task.exception), largo = true)
                    }
                }
        }

        // Enter en la contraseña = pulsar "Entrar"
        inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnLogin.performClick()
                true
            } else false
        }

        // ── Google ──────────────────────────────────────────
        btnGoogle.setOnClickListener { iniciarConGoogle() }

        btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    // ── Inicio de sesión con Google ─────────────────────────

    /**
     * 1. Abre el selector de cuentas de Google (Credential Manager).
     * 2. Recibe un "ID token" de la cuenta elegida.
     * 3. Se lo pasa a Firebase, que inicia sesión (o crea la cuenta si es la primera vez).
     */
    private fun iniciarConGoogle() {
        val webClientId = obtenerWebClientId()
        if (webClientId == null) {
            toast("Google todavía no está configurado en Firebase", largo = true)
            return
        }

        val opcionGoogle = GetSignInWithGoogleOption.Builder(webClientId).build()
        val peticion = GetCredentialRequest.Builder()
            .addCredentialOption(opcionGoogle)
            .build()

        cargando(true)

        lifecycleScope.launch {
            try {
                val resultado = credentialManager.getCredential(this@LoginActivity, peticion)
                val credencial = resultado.credential

                if (credencial is CustomCredential &&
                    credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val cuentaGoogle = GoogleIdTokenCredential.createFrom(credencial.data)
                    entrarEnFirebaseConGoogle(cuentaGoogle.idToken)
                } else {
                    cargando(false)
                    toast("Tipo de cuenta no admitido")
                }
            } catch (e: GetCredentialCancellationException) {
                // El usuario ha cerrado el selector: no mostramos error
                cargando(false)
            } catch (e: NoCredentialException) {
                cargando(false)
                toast("No hay ninguna cuenta de Google en este dispositivo", largo = true)
            } catch (e: GoogleIdTokenParsingException) {
                cargando(false)
                Log.e("Login", "Token de Google no válido", e)
                toast("No se ha podido leer la cuenta de Google")
            } catch (e: GetCredentialException) {
                cargando(false)
                Log.e("Login", "Error de Credential Manager", e)
                toast("No se ha podido iniciar sesión con Google", largo = true)
            }
        }
    }

    private fun entrarEnFirebaseConGoogle(idToken: String) {
        val credencialFirebase = GoogleAuthProvider.getCredential(idToken, null)

        auth.signInWithCredential(credencialFirebase)
            .addOnCompleteListener { task ->
                cargando(false)
                if (task.isSuccessful) {
                    irAMain()
                } else {
                    Log.e("Login", "Firebase rechazó la cuenta de Google", task.exception)
                    toast(mensajeDeError(task.exception), largo = true)
                }
            }
    }

    /**
     * El "Web client ID" lo genera automáticamente el plugin de google-services
     * (R.string.default_web_client_id) cuando Google está activado en Firebase.
     * Lo buscamos así para que la app compile aunque todavía no esté configurado.
     */
    private fun obtenerWebClientId(): String? {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (id != 0) getString(id) else null
    }

    // ── Helpers ─────────────────────────────────────────────

    /** Desactiva los botones mientras se está iniciando sesión. */
    private fun cargando(activo: Boolean) {
        btnLogin.isEnabled = !activo
        btnGoogle.isEnabled = !activo
    }

    private fun mensajeDeError(e: Exception?): String = when (e) {
        is FirebaseAuthInvalidUserException -> "No existe ninguna cuenta con ese correo"
        is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña incorrectos"
        is FirebaseAuthUserCollisionException -> "Ese correo ya está registrado con contraseña. Entra con tu correo"
        is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
        else -> "No se ha podido iniciar sesión"
    }

    private fun toast(mensaje: String, largo: Boolean = false) =
        Toast.makeText(this, mensaje, if (largo) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun irAMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
