package com.exemple.llistacompra

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import java.security.SecureRandom

/*
 * ── Estructura en Firebase ───────────────────────────────────────────
 *
 *  listas/{listaId}/
 *      nombre:        "Mi lista"
 *      propietario:   uid de quien la creó
 *      codigo:        "K7P2QX"            código para unirse
 *      miembros/{uid}:      "K7P2QX"      quién puede verla (se guarda el código con el que entró)
 *      miembrosInfo/{uid}:  "ana@gmail.com"  para mostrar quién está
 *      categorias/...       (igual que antes)
 *
 *  codigos/{codigo}: listaId               para encontrar la lista a partir del código
 *
 *  usuarios/{uid}/
 *      listaActual:   listaId              la que se abre al entrar
 *      listas/{listaId}: true              todas las listas a las que pertenece
 *      aprendido/...                       correcciones del autoclasificador
 */

/** Datos básicos de una lista (para el selector y el diálogo de compartir). */
data class InfoLista(
    val id: String,
    val nombre: String,
    val propietario: String,
    val codigo: String,
    val miembros: Map<String, String>   // uid -> correo
) {
    fun esPropietario(uid: String) = propietario == uid
    val compartida get() = miembros.size > 1

    companion object {
        fun desde(snap: DataSnapshot): InfoLista? {
            val id = snap.key ?: return null
            val propietario = snap.child("propietario").getValue(String::class.java) ?: return null
            return InfoLista(
                id = id,
                nombre = snap.child("nombre").getValue(String::class.java) ?: "Mi lista",
                propietario = propietario,
                codigo = snap.child("codigo").getValue(String::class.java).orEmpty(),
                miembros = snap.child("miembrosInfo").children.associate {
                    (it.key ?: "") to (it.getValue(String::class.java) ?: "")
                }
            )
        }
    }
}

/**
 * Códigos para compartir listas.
 * No se usan caracteres que se confunden entre sí: O/0, I/1/L.
 */
object CodigoLista {
    const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   // 31 caracteres
    const val LONGITUD = 6                                    // 31^6 ≈ 887 millones de códigos
    const val NO_SE_USAN = "O, 0, I, L ni 1"

    private val azar = SecureRandom()

    fun generar(): String = buildString {
        repeat(LONGITUD) { append(ALFABETO[azar.nextInt(ALFABETO.length)]) }
    }

    /** "k7p-2qx " -> "K7P2QX" */
    fun limpiar(texto: String): String = texto.uppercase().filter { it.isLetterOrDigit() }

    /** Caracteres escritos que no existen en los códigos (O, 0, I, L, 1...) */
    fun caracteresNoValidos(codigo: String): Set<Char> = codigo.filter { it !in ALFABETO }.toSet()

    fun esValido(codigo: String) = codigo.length == LONGITUD && caracteresNoValidos(codigo).isEmpty()

    /** "K7P2QX" -> "K7P 2QX" (más fácil de leer y dictar) */
    fun bonito(codigo: String) =
        if (codigo.length == LONGITUD) "${codigo.substring(0, 3)} ${codigo.substring(3)}" else codigo
}

/**
 * Todo lo relacionado con crear, compartir, unirse y salir de listas.
 * Los errores se devuelven como mensajes listos para mostrar al usuario.
 */
class GestorListas(private val uid: String, private val correo: String) {

    private val raiz = FirebaseDatabase.getInstance().reference
    private val usuarioRef = raiz.child("usuarios").child(uid)

    val aprendidoRef get() = usuarioRef.child("aprendido")

    fun refLista(listaId: String) = raiz.child("listas").child(listaId)

    // ── Al entrar ──────────────────────────────────────────

    /**
     * Devuelve la lista que hay que abrir:
     *  1. la última que tenía abierta,
     *  2. si no, la primera de sus listas,
     *  3. si no tiene ninguna, crea "Mi lista" (pasando ahí los datos del formato antiguo si los hay).
     */
    fun prepararListaActual(onListo: (String) -> Unit, onError: (String) -> Unit) {
        usuarioRef.get()
            .addOnSuccessListener { usuario ->
                val actual = usuario.child("listaActual").getValue(String::class.java)
                val primera = usuario.child("listas").children.firstOrNull()?.key
                when {
                    actual != null -> onListo(actual)
                    primera != null -> {
                        usuarioRef.child("listaActual").setValue(primera)
                        onListo(primera)
                    }
                    else -> crearPrimeraLista(onListo, onError)
                }
            }
            .addOnFailureListener { onError("No se han podido cargar tus listas") }
    }

    /** Migración: antes los datos estaban en listas/{uid}. Los movemos a una lista nueva. */
    private fun crearPrimeraLista(onListo: (String) -> Unit, onError: (String) -> Unit) {
        raiz.child("listas").child(uid).get()
            .addOnSuccessListener { antigua ->
                crearLista(
                    nombre = "Mi lista",
                    categorias = antigua.child("categorias").value,
                    aprendido = antigua.child("aprendido").value,
                    borrarAntigua = antigua.exists(),
                    onListo = onListo,
                    onError = onError
                )
            }
            .addOnFailureListener {
                crearLista("Mi lista", null, null, false, onListo, onError)
            }
    }

    // ── Crear ──────────────────────────────────────────────

    fun crearLista(nombre: String, onListo: (String) -> Unit, onError: (String) -> Unit) =
        crearLista(nombre, null, null, false, onListo, onError)

    private fun crearLista(
        nombre: String,
        categorias: Any?,
        aprendido: Any?,
        borrarAntigua: Boolean,
        onListo: (String) -> Unit,
        onError: (String) -> Unit,
        intentos: Int = 3
    ) {
        val listaId = raiz.child("listas").push().key ?: return onError("No se ha podido crear la lista")
        val codigo = CodigoLista.generar()

        // Comprobamos que el código no lo tenga ya otra lista (es casi imposible, pero por si acaso)
        raiz.child("codigos").child(codigo).get()
            .addOnSuccessListener { existente ->
                if (existente.exists()) {
                    if (intentos > 0) crearLista(nombre, categorias, aprendido, borrarAntigua, onListo, onError, intentos - 1)
                    else onError("No se ha podido generar un código. Inténtalo de nuevo")
                    return@addOnSuccessListener
                }

                val lista = mutableMapOf<String, Any?>(
                    "nombre" to nombre,
                    "propietario" to uid,
                    "codigo" to codigo,
                    "miembros" to mapOf(uid to codigo),
                    "miembrosInfo" to mapOf(uid to correo)
                )
                if (categorias != null) lista["categorias"] = categorias

                // Todo en una sola escritura: o se guarda todo o nada
                val cambios = mutableMapOf<String, Any?>(
                    "listas/$listaId" to lista,
                    "codigos/$codigo" to listaId,
                    "usuarios/$uid/listas/$listaId" to true,
                    "usuarios/$uid/listaActual" to listaId
                )
                if (aprendido != null) cambios["usuarios/$uid/aprendido"] = aprendido
                if (borrarAntigua) cambios["listas/$uid"] = null

                raiz.updateChildren(cambios)
                    .addOnSuccessListener { onListo(listaId) }
                    .addOnFailureListener { onError("No se ha podido crear la lista") }
            }
            .addOnFailureListener { onError("Sin conexión. Revisa tu internet") }
    }

    // ── Unirse con código ──────────────────────────────────

    fun unirse(codigoEscrito: String, onListo: (String) -> Unit, onError: (String) -> Unit) {
        val codigo = CodigoLista.limpiar(codigoEscrito)

        raiz.child("codigos").child(codigo).get()
            .addOnSuccessListener { snap ->
                val listaId = snap.getValue(String::class.java)
                if (listaId == null) {
                    onError("No existe ninguna lista con ese código")
                    return@addOnSuccessListener
                }

                val cambios = mapOf<String, Any?>(
                    "listas/$listaId/miembros/$uid" to codigo,
                    "listas/$listaId/miembrosInfo/$uid" to correo,
                    "usuarios/$uid/listas/$listaId" to true,
                    "usuarios/$uid/listaActual" to listaId
                )
                raiz.updateChildren(cambios)
                    .addOnSuccessListener { onListo(listaId) }
                    .addOnFailureListener { onError("No se ha podido unir a la lista") }
            }
            .addOnFailureListener { onError("Sin conexión. Revisa tu internet") }
    }

    // ── Gestión ────────────────────────────────────────────

    fun cambiarListaActual(listaId: String) {
        usuarioRef.child("listaActual").setValue(listaId)
    }

    fun renombrar(listaId: String, nombre: String) {
        refLista(listaId).child("nombre").setValue(nombre)
    }

    /** Solo el propietario. El código viejo deja de funcionar; los miembros actuales se quedan. */
    fun regenerarCodigo(info: InfoLista, onListo: (String) -> Unit, onError: (String) -> Unit) {
        val nuevo = CodigoLista.generar()
        val cambios = mapOf<String, Any?>(
            "codigos/${info.codigo}" to null,
            "codigos/$nuevo" to info.id,
            "listas/${info.id}/codigo" to nuevo
        )
        raiz.updateChildren(cambios)
            .addOnSuccessListener { onListo(nuevo) }
            .addOnFailureListener { onError("No se ha podido cambiar el código") }
    }

    /** Un miembro (no propietario) abandona la lista. */
    fun salir(info: InfoLista, onListo: () -> Unit, onError: (String) -> Unit) {
        val cambios = mapOf<String, Any?>(
            "listas/${info.id}/miembros/$uid" to null,
            "listas/${info.id}/miembrosInfo/$uid" to null,
            "usuarios/$uid/listas/${info.id}" to null,
            "usuarios/$uid/listaActual" to null
        )
        raiz.updateChildren(cambios)
            .addOnSuccessListener { onListo() }
            .addOnFailureListener { onError("No se ha podido salir de la lista") }
    }

    /** El propietario borra la lista entera (para todos). */
    fun eliminar(info: InfoLista, onListo: () -> Unit, onError: (String) -> Unit) {
        val cambios = mapOf<String, Any?>(
            "listas/${info.id}" to null,
            "codigos/${info.codigo}" to null,
            "usuarios/$uid/listas/${info.id}" to null,
            "usuarios/$uid/listaActual" to null
        )
        raiz.updateChildren(cambios)
            .addOnSuccessListener { onListo() }
            .addOnFailureListener { onError("No se ha podido eliminar la lista") }
    }

    /** Cuando ya no tenemos acceso (nos echaron o la borraron): la quitamos de "mis listas". */
    fun olvidar(listaId: String, onListo: () -> Unit) {
        val cambios = mapOf<String, Any?>(
            "usuarios/$uid/listas/$listaId" to null,
            "usuarios/$uid/listaActual" to null
        )
        raiz.updateChildren(cambios).addOnCompleteListener { onListo() }
    }

    /** Todas las listas del usuario, ordenadas por nombre. Limpia las que ya no existen. */
    fun misListas(onListo: (List<InfoLista>) -> Unit) {
        usuarioRef.child("listas").get().addOnSuccessListener { snap ->
            val ids = snap.children.mapNotNull { it.key }
            if (ids.isEmpty()) {
                onListo(emptyList())
                return@addOnSuccessListener
            }

            val resultado = mutableListOf<InfoLista>()
            var pendientes = ids.size

            ids.forEach { id ->
                refLista(id).get().addOnCompleteListener { tarea ->
                    val info = if (tarea.isSuccessful) tarea.result?.let { InfoLista.desde(it) } else null
                    if (info != null) resultado += info
                    else usuarioRef.child("listas").child(id).removeValue()   // ya no existe o no tenemos acceso

                    pendientes--
                    if (pendientes == 0) onListo(resultado.sortedBy { it.nombre.lowercase() })
                }
            }
        }.addOnFailureListener { onListo(emptyList()) }
    }
}
