package com.exemple.llistacompra

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

// ── Modelos ─────────────────────────────────────────────

data class Producto(
    val id: String = "",
    val nombre: String = "",
    val completado: Boolean = false
)

data class Categoria(
    val id: String = "",
    val nombre: String = "",
    val emoji: String = "",
    val creador: String = "",   // uid de quien la creó ("" en categorías antiguas)
    val productos: Map<String, Producto> = emptyMap()
)

// ── Activity ────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    private lateinit var listaProductos: LinearLayout
    private lateinit var spinnerCategoria: Spinner
    private lateinit var tilProducte: TextInputLayout
    private lateinit var inputProducte: TextInputEditText
    private lateinit var contenedorSugerencias: View
    private lateinit var chipsCategoria: ChipGroup

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val uid get() = auth.currentUser?.uid ?: ""

    // ── Listas compartidas ──
    private lateinit var gestor: GestorListas
    private var listaActualId: String? = null      // lista abierta ahora mismo
    private var infoLista: InfoLista? = null       // nombre, código, miembros...
    private lateinit var txtNombreLista: TextView
    private lateinit var txtInfoLista: TextView
    private lateinit var btnLimpieza: Button

    private val categoriasRef get() = gestor.refLista(listaActualId!!).child("categorias")
    private val aprendidoRef get() = gestor.aprendidoRef

    private val categorias = mutableMapOf<String, Categoria>()

    // Estado del spinner: ids en el mismo orden que los nombres mostrados
    private var idsSpinner: List<String> = emptyList()
    private var categoriaSeleccionadaId: String? = null

    // ── Autoclasificación ──
    // Correcciones del usuario: "monster" -> "Bebidas"
    private val aprendido = mutableMapOf<String, String>()
    // Categorías posibles para lo que se está escribiendo, de más a menos probable
    private var candidatos: List<String> = emptyList()
    // Chip que ha tocado el usuario (si ha tocado alguno)
    private var chipElegido: String? = null
    // Categoría sugerida final (puede no existir todavía)
    private var sugerencia: String? = null
    // true si el usuario ha cambiado la categoría a mano: entonces no la pisamos
    private var eleccionManual = false
    private var spinnerTocado = false

    /** Dónde irá el producto. id = null significa que hay que crear la categoría. */
    private data class Destino(val nombre: String, val id: String?)

    // Guardamos el listener para quitarlo al cerrar la Activity
    private var refEscuchada: DatabaseReference? = null
    private var listenerCategorias: ValueEventListener? = null

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sin sesión no tiene sentido estar aquí
        if (auth.currentUser == null) {
            irAlLogin()
            return
        }

        setContentView(R.layout.activity_main)
        configurarBarrasSistema(findViewById(R.id.cabecera))

        val btnAfegir      = findViewById<FloatingActionButton>(R.id.btnAfegir)
        btnLimpieza        = findViewById(R.id.btnLimpieza)
        val btnVolverLogin = findViewById<Button>(R.id.btnVolverLogin)
        val txtUsuario     = findViewById<TextView>(R.id.txtUsuario)
        val btnNuevaCat    = findViewById<Button>(R.id.btnNuevaCategoria)

        inputProducte    = findViewById(R.id.inputProducte)
        tilProducte      = findViewById(R.id.tilProducte)
        contenedorSugerencias = findViewById(R.id.contenedorSugerencias)
        chipsCategoria   = findViewById(R.id.chipsCategoria)
        spinnerCategoria = findViewById(R.id.spinnerCategoria)
        listaProductos   = findViewById(R.id.listaProductos)

        txtNombreLista   = findViewById(R.id.txtNombreLista)
        txtInfoLista     = findViewById(R.id.txtInfoLista)

        configurarSpinner()

        // Mientras escribe, sugerimos categoría
        inputProducte.doAfterTextChanged { actualizarSugerencia() }

        // Listas compartidas
        gestor = GestorListas(uid, auth.currentUser?.email ?: "Usuario")
        findViewById<View>(R.id.btnMisListas).setOnClickListener { mostrarMisListas() }
        findViewById<Button>(R.id.btnCompartir).setOnClickListener { mostrarDialogoCompartir() }

        mostrarInfoUsuario(txtUsuario)
        cargarAprendido()
        abrirListaActual()

        // Enter del teclado
        inputProducte.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnAfegir.performClick()
                true
            } else false
        }

        // ➕ Añadir producto
        btnAfegir.setOnClickListener {
            val nombre = inputProducte.text?.toString()?.trim().orEmpty()

            if (nombre.isBlank()) {
                tilProducte.error = "Introduce un producto"
                return@setOnClickListener
            }

            val destino = categoriaDestino()
            // Si la categoría sugerida no existe todavía, la creamos
            val catId = destino.id
                ?: agregarCategoriaEnFirebase(destino.nombre, Clasificador.emojiPara(destino.nombre))
                ?: return@setOnClickListener

            agregarProductoEnFirebase(catId, nombre)
            aprender(nombre, destino.nombre)

            // Reiniciamos para el siguiente producto
            eleccionManual = false
            chipElegido = null
            sugerencia = null
            inputProducte.text?.clear()
            ocultarTeclado(inputProducte)
        }

        btnNuevaCat.setOnClickListener { mostrarDialogoCategoria(null) }

        btnLimpieza.setOnClickListener {
            if (!esDueno()) {
                toast("Solo el dueño de la lista puede borrar productos")
                return@setOnClickListener
            }
            val hayCompletados = categorias.values.any { cat -> cat.productos.values.any { it.completado } }
            if (!hayCompletados) {
                toast("No hay productos completados")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Limpiar completados")
                .setMessage("¿Eliminar todos los productos completados?")
                .setPositiveButton("Eliminar") { _, _ -> limpiarCompletados() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnVolverLogin.setOnClickListener {
            auth.signOut()
            // Olvidamos también la cuenta de Google elegida, para poder escoger otra la próxima vez
            lifecycleScope.launch {
                try {
                    CredentialManager.create(this@MainActivity)
                        .clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Logout", "No se pudo limpiar la sesión de Google", e)
                }
                irAlLogin()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dejarDeEscuchar()
    }

    // ── Firebase ─────────────────────────────────────────

    /** Abre la última lista usada (o crea "Mi lista" la primera vez). */
    private fun abrirListaActual() {
        txtNombreLista.text = "Cargando…"
        txtInfoLista.text = ""
        gestor.prepararListaActual(onListo = ::abrirLista, onError = { toast(it) })
    }

    /** Cambia a otra lista: deja de escuchar la anterior y escucha la nueva. */
    private fun abrirLista(listaId: String) {
        dejarDeEscuchar()
        listaActualId = listaId
        infoLista = null
        categorias.clear()
        categoriaSeleccionadaId = null
        reconstruirUI()
        actualizarSpinner()

        gestor.cambiarListaActual(listaId)
        escucharLista()
    }

    private fun dejarDeEscuchar() {
        listenerCategorias?.let { refEscuchada?.removeEventListener(it) }
        listenerCategorias = null
        refEscuchada = null
    }

    /** Si nos han quitado el acceso (o se ha borrado la lista), pasamos a otra. */
    private fun perderAcceso() {
        val id = listaActualId ?: return
        dejarDeEscuchar()
        listaActualId = null
        toast("Ya no tienes acceso a esa lista")
        gestor.olvidar(id) { abrirListaActual() }
    }

    /** Un solo listener para toda la lista: nombre, miembros y categorías. */
    private fun escucharLista() {
        val id = listaActualId ?: return

        val ref = gestor.refLista(id)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val info = InfoLista.desde(snapshot)
                if (info == null) {
                    perderAcceso()
                    return
                }
                infoLista = info
                actualizarCabecera(info)

                categorias.clear()

                snapshot.child("categorias").children.forEach { catSnap ->
                    val catId  = catSnap.key ?: return@forEach
                    val nombre = catSnap.child("nombre").getValue(String::class.java) ?: return@forEach

                    val productos = mutableMapOf<String, Producto>()

                    catSnap.child("productos").children.forEach { prodSnap ->
                        val prodId = prodSnap.key ?: return@forEach
                        val prodNombre = prodSnap.child("nombre").getValue(String::class.java) ?: return@forEach
                        val completado = prodSnap.child("completado").getValue(Boolean::class.java) ?: false

                        productos[prodId] = Producto(prodId, prodNombre, completado)
                    }

                    val emoji = catSnap.child("emoji").getValue(String::class.java).orEmpty()
                    val creador = catSnap.child("creador").getValue(String::class.java).orEmpty()
                    categorias[catId] = Categoria(catId, nombre, emoji, creador, productos)
                }

                reconstruirUI()
                actualizarSpinner()
                // Por si hay algo escrito y acaba de aparecer su categoría
                if (!inputProducte.text.isNullOrBlank()) actualizarSugerencia()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", error.message)
                if (error.code == DatabaseError.PERMISSION_DENIED) perderAcceso()
                else toast("Error al cargar los datos")
            }
        }

        ref.addValueEventListener(listener)
        refEscuchada = ref
        listenerCategorias = listener
    }

    // ── Listas compartidas: cabecera y diálogos ──────────

    private fun actualizarCabecera(info: InfoLista) {
        txtNombreLista.text = "${info.nombre}  ▾"
        txtInfoLista.text = when {
            !info.esPropietario(uid) -> "👥 Te han invitado · ${info.miembros.size} personas"
            info.compartida -> "👥 Compartida · ${info.miembros.size} personas"
            else -> "Solo tú · pulsa Compartir para invitar"
        }
        btnLimpieza.visibility = if (info.esPropietario(uid)) View.VISIBLE else View.GONE
    }

    /** Selector de listas: cambiar, crear una nueva o unirse con código. */
    private fun mostrarMisListas() {
        gestor.misListas { listas ->
            val textos = listas.map { lista ->
                val marca = if (lista.id == listaActualId) "✓ " else "    "
                val compartida = if (lista.compartida) "  👥 ${lista.miembros.size}" else ""
                "$marca${lista.nombre}$compartida"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Mis listas")
                .setItems(textos) { _, i ->
                    val elegida = listas[i]
                    if (elegida.id != listaActualId) abrirLista(elegida.id)
                }
                .setPositiveButton("Nueva lista") { _, _ -> mostrarDialogoNuevaLista() }
                .setNeutralButton("Unirme con código") { _, _ -> mostrarDialogoUnirse() }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun mostrarDialogoNuevaLista() {
        mostrarDialogoTexto("Nueva lista", "Nombre de la lista (ej. Cumple de Ana)", "", "Crear") { nombre ->
            gestor.crearLista(nombre, onListo = { id ->
                abrirLista(id)
                toast("Lista «$nombre» creada")
            }, onError = { toast(it) })
        }
    }

    /** Escribir un código para unirse a la lista de otra persona. */
    private fun mostrarDialogoUnirse() {
        val vista = layoutInflater.inflate(R.layout.dialogo_unirse, null)
        val til = vista.findViewById<TextInputLayout>(R.id.tilCodigo)
        val input = vista.findViewById<TextInputEditText>(R.id.inputCodigo)

        // Siempre en mayúsculas
        input.filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(7))

        // Aviso al momento si escribe una letra que no existe en los códigos
        input.doAfterTextChanged { texto ->
            val malos = CodigoLista.caracteresNoValidos(CodigoLista.limpiar(texto?.toString().orEmpty()))
            til.error = if (malos.isEmpty()) null
            else "Los códigos no llevan ${malos.joinToString(", ")} (no se usan ${CodigoLista.NO_SE_USAN})"
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Unirme a una lista")
            .setView(vista)
            .setPositiveButton("Unirme", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.setOnShowListener {
            val btnUnirme = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            btnUnirme.setOnClickListener {
                val codigo = CodigoLista.limpiar(input.text?.toString().orEmpty())
                when {
                    codigo.length != CodigoLista.LONGITUD ->
                        til.error = "El código tiene ${CodigoLista.LONGITUD} caracteres"
                    !CodigoLista.esValido(codigo) ->
                        til.error = "Ese código no es válido (no se usan ${CodigoLista.NO_SE_USAN})"
                    else -> {
                        btnUnirme.isEnabled = false
                        gestor.unirse(codigo,
                            onListo = { id ->
                                dialogo.dismiss()
                                abrirLista(id)
                                toast("¡Te has unido a la lista!")
                            },
                            onError = { mensaje ->
                                btnUnirme.isEnabled = true
                                til.error = mensaje
                            })
                    }
                }
            }
        }
        dialogo.show()
    }

    /** Muestra el código para compartir y las opciones de la lista. */
    private fun mostrarDialogoCompartir() {
        val info = infoLista ?: return toast("La lista todavía se está cargando")
        val soyPropietario = info.esPropietario(uid)

        val vista = layoutInflater.inflate(R.layout.dialogo_compartir, null)
        val txtExplicacion = vista.findViewById<TextView>(R.id.txtCompartirExplicacion)
        val txtCodigo = vista.findViewById<TextView>(R.id.txtCodigo)
        val txtMiembros = vista.findViewById<TextView>(R.id.txtMiembros)
        val btnCopiar = vista.findViewById<Button>(R.id.btnCopiarCodigo)
        val btnEnviar = vista.findViewById<Button>(R.id.btnEnviarCodigo)
        val btnNombre = vista.findViewById<Button>(R.id.btnCambiarNombre)
        val btnRegenerar = vista.findViewById<Button>(R.id.btnRegenerarCodigo)
        val btnSalir = vista.findViewById<Button>(R.id.btnSalirLista)

        var codigo = info.codigo

        txtExplicacion.text = "Pasa este código a quien quieras. Podrá ver y editar «${info.nombre}» en tiempo real."
        txtCodigo.text = CodigoLista.bonito(codigo)
        txtMiembros.text = info.miembros.entries.joinToString("\n") { (miembroUid, correo) ->
            val etiquetas = buildList {
                if (miembroUid == info.propietario) add("creador")
                if (miembroUid == uid) add("tú")
            }
            "• $correo" + if (etiquetas.isNotEmpty()) "  (${etiquetas.joinToString(", ")})" else ""
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle("Compartir lista")
            .setView(vista)
            .setPositiveButton("Cerrar", null)
            .create()

        btnCopiar.setOnClickListener {
            val portapapeles = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            portapapeles.setPrimaryClip(android.content.ClipData.newPlainText("Código de lista", codigo))
            toast("Código copiado")
        }

        btnEnviar.setOnClickListener {
            val texto = "¡Únete a mi lista de la compra «${info.nombre}»! 🛒\n" +
                    "Abre la app, toca el nombre de la lista → «Unirme con código» y escribe:\n\n" +
                    CodigoLista.bonito(codigo)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, texto)
            }
            startActivity(Intent.createChooser(intent, "Enviar código"))
        }

        btnNombre.setOnClickListener {
            dialogo.dismiss()
            mostrarDialogoTexto("Cambiar nombre", "Nombre de la lista", info.nombre, "Guardar") { nuevo ->
                gestor.renombrar(info.id, nuevo)
            }
        }

        // Solo el creador puede cambiar el código
        btnRegenerar.visibility = if (soyPropietario) View.VISIBLE else View.GONE
        btnRegenerar.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Generar un código nuevo?")
                .setMessage("El código actual dejará de funcionar. Quienes ya están en la lista se quedan.")
                .setPositiveButton("Generar") { _, _ ->
                    gestor.regenerarCodigo(info, onListo = { nuevo ->
                        codigo = nuevo
                        txtCodigo.text = CodigoLista.bonito(nuevo)
                    }, onError = { toast(it) })
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // El creador elimina la lista; el resto solo se sale
        btnSalir.text = if (soyPropietario) "Eliminar lista" else "Salir de la lista"
        btnSalir.setOnClickListener {
            val (titulo, mensaje) = if (soyPropietario)
                "¿Eliminar «${info.nombre}»?" to "Se borrará para todos los miembros. No se puede deshacer."
            else
                "¿Salir de «${info.nombre}»?" to "Dejarás de verla. Podrás volver a entrar con el código."

            AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(mensaje)
                .setPositiveButton(if (soyPropietario) "Eliminar" else "Salir") { _, _ ->
                    dialogo.dismiss()
                    val alTerminar = {
                        dejarDeEscuchar()
                        listaActualId = null
                        abrirListaActual()
                    }
                    if (soyPropietario) gestor.eliminar(info, alTerminar) { toast(it) }
                    else gestor.salir(info, alTerminar) { toast(it) }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        dialogo.show()
    }

    // ── Autoclasificación ────────────────────────────────

    /** Detecta cuándo es el USUARIO quien cambia el spinner (y no nosotros por código). */
    @SuppressLint("ClickableViewAccessibility")
    private fun configurarSpinner() {
        spinnerCategoria.setOnTouchListener { _, evento ->
            if (evento.action == MotionEvent.ACTION_UP) spinnerTocado = true
            false
        }

        spinnerCategoria.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val elegida = idsSpinner.getOrNull(position)
                categoriaSeleccionadaId = elegida

                // El usuario ha elegido a mano algo distinto a lo sugerido -> se respeta
                if (spinnerTocado && elegida != idSugerido()) {
                    eleccionManual = true
                    pintarChips()   // ya ha decidido: escondemos los chips
                }
                spinnerTocado = false

                actualizarAyuda()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Se llama cada vez que cambia el texto del producto. */
    private fun actualizarSugerencia() {
        tilProducte.error = null
        val nombre = inputProducte.text?.toString()?.trim().orEmpty()

        if (nombre.isEmpty()) {
            candidatos = emptyList()
            chipElegido = null
            sugerencia = null
            eleccionManual = false
            pintarChips()
            actualizarAyuda()
            return
        }

        candidatos = Clasificador.candidatos(nombre, aprendido)

        // Si había tocado un chip y sigue siendo válido, lo respetamos
        val elegido = chipElegido
        if (elegido != null && candidatos.none { Clasificador.mismoNombre(it, elegido) }) chipElegido = null
        sugerencia = chipElegido ?: candidatos.firstOrNull()

        // Si no la ha cambiado a mano, movemos el spinner a la categoría sugerida
        if (!eleccionManual) idSugerido()?.let { seleccionarEnSpinner(it) }

        pintarChips()
        actualizarAyuda()
    }

    /** Muestra un chip por categoría posible cuando hay más de una. */
    private fun pintarChips() {
        val mostrar = candidatos.size >= 2 && !eleccionManual
        contenedorSugerencias.visibility = if (mostrar) View.VISIBLE else View.GONE
        chipsCategoria.removeAllViews()
        if (!mostrar) return

        candidatos.forEach { nombre ->
            val chip = layoutInflater.inflate(R.layout.item_chip_categoria, chipsCategoria, false) as Chip
            chip.id = View.generateViewId()
            chip.text = nombre
            chip.isChecked = sugerencia?.let { Clasificador.mismoNombre(it, nombre) } == true
            chip.setOnClickListener { elegirChip(nombre) }
            chipsCategoria.addView(chip)
        }
    }

    /** El usuario toca un chip: esa pasa a ser la categoría de destino. */
    private fun elegirChip(nombre: String) {
        chipElegido = nombre
        sugerencia = nombre
        eleccionManual = false
        idSugerido()?.let { seleccionarEnSpinner(it) }
        actualizarAyuda()
    }

    /** Texto de ayuda bajo el campo: dónde se va a guardar el producto. */
    private fun actualizarAyuda() {
        val nombre = inputProducte.text?.toString()?.trim().orEmpty()
        if (nombre.isEmpty()) {
            tilProducte.helperText = null
            return
        }

        val destino = categoriaDestino()
        val etiqueta = when {
            destino.id == null -> " (nueva categoría)"
            chipElegido != null -> ""
            !eleccionManual && sugerencia != null -> " · sugerida"
            else -> ""
        }
        tilProducte.helperText = "Se añadirá a: ${destino.nombre}$etiqueta"
    }

    private fun categoriaDestino(): Destino {
        // 1. La sugerencia, si el usuario no ha elegido otra cosa
        if (!eleccionManual) {
            sugerencia?.let { s ->
                val existente = buscarCategoriaPorNombre(s)
                return Destino(existente?.nombre ?: s, existente?.id)
            }
        }
        // 2. Lo que haya en el spinner
        categoriaSeleccionadaId?.let { id -> categorias[id]?.let { return Destino(it.nombre, it.id) } }
        // 3. Si no hay ninguna categoría: "Otros"
        return Destino(Clasificador.OTROS, buscarCategoriaPorNombre(Clasificador.OTROS)?.id)
    }

    private fun idSugerido(): String? = sugerencia?.let { buscarCategoriaPorNombre(it)?.id }

    private fun buscarCategoriaPorNombre(nombre: String): Categoria? =
        categorias.values.firstOrNull { Clasificador.mismoNombre(it.nombre, nombre) }

    private fun seleccionarEnSpinner(catId: String) {
        val index = idsSpinner.indexOf(catId)
        if (index >= 0 && spinnerCategoria.selectedItemPosition != index) {
            spinnerCategoria.setSelection(index)
        }
        categoriaSeleccionadaId = catId
    }

    /**
     * Guarda la corrección del usuario para la próxima vez.
     * Si coincide con lo que ya diría el diccionario, no hace falta guardarla.
     */
    private fun aprender(producto: String, categoria: String) {
        if (uid.isEmpty()) return
        val clave = Clasificador.clave(producto)
        if (clave.isEmpty()) return

        val porDiccionario = Clasificador.sugerirPorDiccionario(producto)
        if (porDiccionario != null && Clasificador.mismoNombre(porDiccionario, categoria)) {
            if (aprendido.remove(clave) != null) aprendidoRef.child(clave).removeValue()
            return
        }

        if (aprendido[clave] != categoria) {
            aprendido[clave] = categoria
            aprendidoRef.child(clave).setValue(categoria)
        }
    }

    private fun cargarAprendido() {
        if (uid.isEmpty()) return
        aprendidoRef.get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { hijo ->
                val clave = hijo.key ?: return@forEach
                val categoria = hijo.getValue(String::class.java) ?: return@forEach
                aprendido[clave] = categoria
            }
        }
    }

    // ── CRUD ─────────────────────────────────────────────

    /** Crea la categoría, la deja seleccionada y devuelve su id. */
    private fun agregarCategoriaEnFirebase(nombre: String, emoji: String): String? {
        if (listaActualId == null) return null
        val catId = categoriasRef.push().key ?: return null
        categoriaSeleccionadaId = catId
        // Guardamos quién la crea: solo esa persona (o el dueño de la lista) podrá eliminarla
        categoriasRef.child(catId).setValue(mapOf("nombre" to nombre, "emoji" to emoji, "creador" to uid))
        return catId
    }

    /** Mueve un producto de una categoría a otra en una sola escritura. */
    private fun moverProducto(origenId: String, destinoId: String, producto: Producto) {
        if (listaActualId == null || origenId == destinoId) return
        val cambios = mapOf<String, Any?>(
            "$origenId/productos/${producto.id}" to null,
            "$destinoId/productos/${producto.id}" to mapOf(
                "nombre" to producto.nombre,
                "completado" to producto.completado
            )
        )
        categoriasRef.updateChildren(cambios)
    }

    /** Cambia nombre y emoji sin tocar los productos de la categoría. */
    private fun editarCategoriaEnFirebase(catId: String, nuevoNombre: String, nuevoEmoji: String) {
        if (listaActualId == null) return
        categoriasRef.child(catId).updateChildren(mapOf("nombre" to nuevoNombre, "emoji" to nuevoEmoji))
    }

    private fun eliminarCategoriaEnFirebase(catId: String) {
        if (listaActualId == null) return
        categoriasRef.child(catId).removeValue()
    }

    private fun agregarProductoEnFirebase(catId: String, nombre: String) {
        if (listaActualId == null) return
        val productosRef = categoriasRef.child(catId).child("productos")
        val prodId = productosRef.push().key ?: return

        val producto = mapOf(
            "nombre" to nombre,
            "completado" to false
        )

        productosRef.child(prodId).setValue(producto)
    }

    private fun toggleProductoCompletado(catId: String, prodId: String, completado: Boolean) {
        if (listaActualId == null) return
        categoriasRef.child(catId).child("productos").child(prodId)
            .child("completado").setValue(completado)
    }

    private fun editarProductoEnFirebase(catId: String, prodId: String, nuevoNombre: String) {
        if (listaActualId == null) return
        categoriasRef.child(catId).child("productos").child(prodId)
            .child("nombre").setValue(nuevoNombre)
    }

    private fun eliminarProductoEnFirebase(catId: String, prodId: String) {
        if (listaActualId == null) return
        categoriasRef.child(catId).child("productos").child(prodId).removeValue()
    }

    // Borra todos los completados en UNA sola escritura
    private fun limpiarCompletados() {
        if (listaActualId == null) return

        val borrados = mutableMapOf<String, Any?>()
        categorias.forEach { (catId, cat) ->
            cat.productos.values.filter { it.completado }.forEach { prod ->
                borrados["$catId/productos/${prod.id}"] = null
            }
        }

        if (borrados.isNotEmpty()) categoriasRef.updateChildren(borrados)
    }

    // ── Diálogos de editar / eliminar ────────────────────

    /**
     * Puede eliminar una categoría:
     *  - el dueño de la lista (cualquiera)
     *  - quien la creó (solo las suyas)
     * Las reglas de Firebase comprueban lo mismo.
     */
    private fun puedeEliminar(categoria: Categoria): Boolean =
        esDueno() || (categoria.creador == uid && categoria.productos.isEmpty())

    /** ¿Soy el dueño de la lista abierta? (el dueño puede hacerlo todo) */
    private fun esDueno(): Boolean = infoLista?.esPropietario(uid) == true

    private fun mostrarOpcionesCategoria(categoria: Categoria) {
        val titulo = "${emojiDe(categoria)} ${categoria.nombre}"

        if (puedeEliminar(categoria)) {
            AlertDialog.Builder(this)
                .setTitle(titulo)
                .setItems(arrayOf("Editar nombre e icono", "Eliminar")) { _, opcion ->
                    when (opcion) {
                        0 -> mostrarDialogoCategoria(categoria)
                        1 -> confirmarEliminarCategoria(categoria)
                    }
                }
                .show()
        } else {
            // Invitado: puede editarla, pero le explicamos por qué no puede eliminarla
            AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(
                    if (categoria.creador == uid)
                        "Solo puedes eliminar tus categorías cuando están vacías. Pide al dueño de la lista que borre los productos."
                    else
                        "Solo el dueño de la lista o quien creó esta categoría puede eliminarla."
                )
                .setPositiveButton("Editar nombre e icono") { _, _ -> mostrarDialogoCategoria(categoria) }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun confirmarEliminarCategoria(categoria: Categoria) {
        if (!puedeEliminar(categoria)) {
            toast("No tienes permiso para eliminar esta categoría")
            return
        }
        val total = categoria.productos.size
        val mensaje = if (total == 0) "¿Eliminar la categoría \"${categoria.nombre}\"?"
        else "Se eliminará \"${categoria.nombre}\" y sus $total producto(s). ¿Continuar?"

        AlertDialog.Builder(this)
            .setTitle("Eliminar categoría")
            .setMessage(mensaje)
            .setPositiveButton("Eliminar") { _, _ -> eliminarCategoriaEnFirebase(categoria.id) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarOpcionesProducto(catId: String, producto: Producto) {
        // Los invitados solo pueden añadir y tachar productos
        if (!esDueno()) {
            AlertDialog.Builder(this)
                .setTitle(producto.nombre)
                .setMessage("Solo el dueño de la lista puede editar, mover o eliminar productos.\n\nTú puedes añadir productos y tacharlos.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(producto.nombre)
            .setItems(arrayOf("Editar", "Cambiar de categoría", "Eliminar")) { _, opcion ->
                when (opcion) {
                    0 -> mostrarDialogoTexto("Editar producto", "Nombre del producto", producto.nombre, "Guardar") { nuevo ->
                        editarProductoEnFirebase(catId, producto.id, nuevo)
                    }
                    1 -> elegirNuevaCategoria(catId, producto)
                    2 -> eliminarProductoEnFirebase(catId, producto.id)
                }
            }
            .show()
    }

    /** Lista de categorías para mover el producto. La app aprende la elección. */
    private fun elegirNuevaCategoria(catActualId: String, producto: Producto) {
        val otras = categorias.values
            .filter { it.id != catActualId }
            .sortedBy { it.nombre.lowercase() }

        if (otras.isEmpty()) {
            toast("No hay otras categorías. Crea una con \"Nueva\"")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Mover \"${producto.nombre}\" a…")
            .setItems(otras.map { it.nombre }.toTypedArray()) { _, i ->
                val destino = otras[i]
                moverProducto(catActualId, destino.id, producto)
                aprender(producto.nombre, destino.nombre)
                toast("Movido a ${destino.nombre}.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Diálogo reutilizable con un campo de texto
    private fun mostrarDialogoTexto(
        titulo: String,
        pista: String,
        textoInicial: String,
        textoBoton: String,
        onAceptar: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = pista
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(textoInicial)
            setSelection(text.length)
        }
        val contenedor = FrameLayout(this).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(contenedor)
            .setPositiveButton(textoBoton) { _, _ ->
                val texto = input.text.toString().trim()
                if (texto.isEmpty()) toast("El nombre no puede estar vacío")
                else onAceptar(texto)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── UI ───────────────────────────────────────────────

    private fun reconstruirUI() {
        listaProductos.removeAllViews()

        if (categorias.isEmpty()) {
            mostrarEstadoVacio()
            return
        }

        categorias.values.sortedBy { it.nombre.lowercase() }.forEach {
            listaProductos.addView(crearVistaCategoria(it))
        }
    }

    private fun crearVistaCategoria(categoria: Categoria): LinearLayout {
        // Cada categoría es una tarjeta blanca
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_categoria)
            setPadding(16.dp, 12.dp, 12.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp }
        }

        // Cabecera: nombre a la izquierda, contador "pendientes/total" a la derecha
        val total = categoria.productos.size
        val pendientes = categoria.productos.values.count { !it.completado }

        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp, 4.dp, 8.dp)
            setOnLongClickListener {
                mostrarOpcionesCategoria(categoria)
                true
            }
        }

        // Emoji: al tocarlo se abre el editor de la categoría
        cabecera.addView(TextView(this).apply {
            text = emojiDe(categoria)
            textSize = 20f
            setPadding(0, 0, 8.dp, 0)
            contentDescription = "Cambiar icono de ${categoria.nombre}"
            setOnClickListener { mostrarDialogoCategoria(categoria) }
        })

        cabecera.addView(TextView(this).apply {
            text = categoria.nombre.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (total > 0) {
            cabecera.addView(TextView(this).apply {
                text = "$pendientes/$total"
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
            })
        }

        contenedor.addView(cabecera)

        if (categoria.productos.isEmpty()) {
            contenedor.addView(TextView(this).apply {
                text = "Sin productos todavía"
                setTextColor(color(R.color.text_secondary))
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(4.dp, 0, 0, 8.dp)
            })
        } else {
            // Pendientes primero, completados al final; alfabético dentro de cada grupo
            categoria.productos.values
                .sortedWith(compareBy<Producto>({ it.completado }, { it.nombre.lowercase() }))
                .forEach { contenedor.addView(crearVistaProducto(categoria.id, it)) }
        }

        return contenedor
    }

    private fun crearVistaProducto(catId: String, producto: Producto): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dp
        }

        val checkBox = CheckBox(this).apply {
            isChecked = producto.completado
            setOnCheckedChangeListener { _, isChecked ->
                toggleProductoCompletado(catId, producto.id, isChecked)
            }
        }

        val tv = TextView(this).apply {
            text = producto.nombre
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (producto.completado) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setTextColor(color(R.color.text_secondary))
            } else {
                setTextColor(color(R.color.text_primary))
            }
            // Tocar el texto también marca/desmarca
            setOnClickListener { checkBox.toggle() }
        }

        val abrirOpciones = View.OnLongClickListener {
            mostrarOpcionesProducto(catId, producto)
            true
        }
        tv.setOnLongClickListener(abrirOpciones)
        fila.setOnLongClickListener(abrirOpciones)

        fila.addView(checkBox)
        fila.addView(tv)

        return fila
    }

    private fun actualizarSpinner() {
        val ordenadas = categorias.values.sortedBy { it.nombre.lowercase() }
        idsSpinner = ordenadas.map { it.id }

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            ordenadas.map { "${emojiDe(it)}  ${it.nombre}" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategoria.adapter = adapter

        if (idsSpinner.isEmpty()) {
            categoriaSeleccionadaId = null
            return
        }

        // Mantenemos la categoría que estaba elegida (o la primera si ya no existe)
        val index = idsSpinner.indexOf(categoriaSeleccionadaId).takeIf { it >= 0 } ?: 0
        spinnerCategoria.setSelection(index, false)
        categoriaSeleccionadaId = idsSpinner[index]
    }

    // ── Helpers ──────────────────────────────────────────

    /** ¿Hay otra categoría con ese nombre? (se excluye la que se está editando) */
    private fun existeOtraCategoria(nombre: String, exceptoId: String?) =
        categorias.values.any { it.id != exceptoId && Clasificador.mismoNombre(it.nombre, nombre) }

    /** Emoji guardado, o uno por defecto según el nombre (para categorías antiguas sin emoji). */
    private fun emojiDe(categoria: Categoria) =
        categoria.emoji.ifBlank { Clasificador.emojiPara(categoria.nombre) }

    // ── Diálogo de categoría (crear / editar) ────────────

    /**
     * Si [existente] es null crea una categoría nueva; si no, la edita.
     * Mientras no se toque el emoji, se va sugiriendo uno según el nombre que se escribe.
     */
    private fun mostrarDialogoCategoria(existente: Categoria?) {
        val vista = layoutInflater.inflate(R.layout.dialogo_categoria, null)
        val txtEmoji = vista.findViewById<TextView>(R.id.txtEmojiElegido)
        val tilNombre = vista.findViewById<TextInputLayout>(R.id.tilNombreCategoria)
        val inputNombre = vista.findViewById<TextInputEditText>(R.id.inputNombreCategoria)
        val grid = vista.findViewById<GridView>(R.id.gridEmojis)
        val tilOtro = vista.findViewById<TextInputLayout>(R.id.tilOtroEmoji)
        val inputOtro = vista.findViewById<TextInputEditText>(R.id.inputOtroEmoji)

        var emojiElegido = existente?.let { emojiDe(it) } ?: Clasificador.EMOJI_POR_DEFECTO
        var emojiTocado = existente != null   // al editar no cambiamos el emoji solo

        val adapter = EmojiAdapter(Clasificador.EMOJIS_DISPONIBLES, emojiElegido)
        grid.adapter = adapter

        fun elegir(emoji: String, aMano: Boolean) {
            emojiElegido = emoji
            if (aMano) emojiTocado = true
            txtEmoji.text = emoji
            adapter.seleccionar(emoji)
        }

        txtEmoji.text = emojiElegido
        inputNombre.setText(existente?.nombre.orEmpty())
        inputNombre.setSelection(inputNombre.text?.length ?: 0)

        // Sugerencia automática de emoji mientras escribe el nombre
        inputNombre.doAfterTextChanged { texto ->
            tilNombre.error = null
            if (!emojiTocado) elegir(Clasificador.emojiPara(texto.toString()), aMano = false)
        }

        grid.setOnItemClickListener { _, _, posicion, _ ->
            inputOtro.text?.clear()
            elegir(adapter.getItem(posicion), aMano = true)
        }

        // Cualquier emoji escrito con el teclado
        inputOtro.doAfterTextChanged { texto ->
            val escrito = texto?.toString().orEmpty()
            if (escrito.isBlank()) {
                tilOtro.error = null
                return@doAfterTextChanged
            }
            val emoji = primerEmoji(escrito)
            if (emoji == null) {
                tilOtro.error = "Eso no es un emoji"
            } else {
                tilOtro.error = null
                elegir(emoji, aMano = true)
            }
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (existente == null) "Nueva categoría" else "Editar categoría")
            .setView(vista)
            .setPositiveButton(if (existente == null) "Crear" else "Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        // Validamos sin cerrar el diálogo si hay errores
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = inputNombre.text?.toString()?.trim().orEmpty()
                when {
                    nombre.isEmpty() -> tilNombre.error = "Ponle un nombre"
                    existeOtraCategoria(nombre, existente?.id) -> tilNombre.error = "Ya existe esa categoría"
                    else -> {
                        if (existente == null) agregarCategoriaEnFirebase(nombre, emojiElegido)
                        else editarCategoriaEnFirebase(existente.id, nombre, emojiElegido)
                        dialogo.dismiss()
                    }
                }
            }
        }

        dialogo.show()
    }

    /**
     * Devuelve el primer emoji del texto, o null si lo primero es una letra/número.
     * Usa el BreakIterator de ICU para no partir emojis compuestos (👨‍👩‍👧, 🇪🇸...).
     */
    private fun primerEmoji(texto: String): String? {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return null

        val iterador = android.icu.text.BreakIterator.getCharacterInstance()
        iterador.setText(limpio)
        val fin = iterador.next()
        val primero = if (fin > 0) limpio.substring(0, fin) else limpio

        val codigo = primero.codePointAt(0)
        return if (Character.isLetterOrDigit(codigo) || Character.isWhitespace(codigo)) null else primero
    }

    /** Adaptador de la cuadrícula: marca con un recuadro el emoji seleccionado. */
    private inner class EmojiAdapter(
        private val emojis: List<String>,
        private var seleccionado: String
    ) : BaseAdapter() {

        fun seleccionar(emoji: String) {
            seleccionado = emoji
            notifyDataSetChanged()
        }

        override fun getCount() = emojis.size
        override fun getItem(position: Int) = emojis[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val vista = (convertView as? TextView)
                ?: layoutInflater.inflate(R.layout.item_emoji, parent, false) as TextView
            val emoji = emojis[position]
            vista.text = emoji
            if (emoji == seleccionado) vista.setBackgroundResource(R.drawable.bg_emoji_seleccionado)
            else vista.background = null
            return vista
        }
    }

    private fun mostrarEstadoVacio() {
        val tv = TextView(this).apply {
            text = "Todavía no hay categorías.\nPulsa \"Nueva\" para crear la primera."
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 48.dp, 0, 0)
        }
        listaProductos.addView(tv)
    }

    private fun mostrarInfoUsuario(tv: TextView) {
        val user = auth.currentUser
        val nombre = user?.email?.substringBefore("@") ?: "Usuario"
        tv.text = nombre
    }

    private fun ocultarTeclado(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(mensaje: String) =
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()

    private fun irAlLogin() {
        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}