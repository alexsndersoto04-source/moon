package com.airgesture.transfer.jarvis.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.airgesture.transfer.jarvis.data.ProjectStore
import com.airgesture.transfer.jarvis.util.JarvisNotifications
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Proyectos, tareas, recordatorios y notas de JARVIS.
 * También puedes crearlos por voz: «crea un proyecto llamado…», «añade tarea…».
 */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var store: ProjectStore
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 15, 25))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cardBg(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.rgb(17, 24, 39))
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun refresh() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "Mis proyectos"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Crea proyectos por voz o aquí. JARVIS los recuerda y te ayuda con las tareas."
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(button("＋ Nuevo proyecto") { showCreateProjectDialog() })

        val projects = store.getProjects()
        if (projects.isEmpty()) {
            root.addView(emptyText("Aún no tienes proyectos. Di «crea un proyecto llamado Mi app» o toca el botón de arriba."))
        }
        for (project in projects) {
            root.addView(projectCard(project))
        }

        root.addView(section("Recordatorios"))
        val reminders = store.getReminders()
        if (reminders.isEmpty()) {
            root.addView(emptyText("Sin recordatorios. Di «recuérdame comprar pan en 10 minutos»."))
        }
        for (reminder in reminders) {
            val whenText = SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES")).format(Date(reminder.triggerAt))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = cardBg()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val text = TextView(this).apply {
                this.text = "⏰ ${reminder.title}\n$whenText" + if (reminder.note.isNotBlank()) "\n${reminder.note}" else ""
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(text)
            card.addView(smallButton("Borrar") {
                JarvisNotifications.cancel(this@ProjectsActivity, reminder.id)
                store.deleteReminder(reminder.id)
                refresh()
            })
            root.addView(card)
        }

        root.addView(section("Notas"))
        val notes = store.getNotes()
        if (notes.isEmpty()) {
            root.addView(emptyText("Sin notas. Di «apunta comprar cables USB»."))
        }
        for (note in notes) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = cardBg()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            }
            val text = TextView(this).apply {
                this.text = "${note.title}\n${note.body}"
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(text)
            card.addView(smallButton("Borrar") {
                store.deleteNote(note.id)
                refresh()
            })
            root.addView(card)
        }
    }

    private fun projectCard(project: ProjectStore.Project): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        val pending = project.tasks.count { !it.done }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = project.name
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)
        header.addView(smallButton("Borrar") {
            store.deleteProject(project.id)
            refresh()
        })
        card.addView(header)
        if (project.description.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = project.description
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
            })
        }
        card.addView(TextView(this).apply {
            text = "$pending pendientes de ${project.tasks.size}"
            textSize = 12f
            setTextColor(Color.rgb(34, 211, 238))
            setPadding(0, dp(4), 0, dp(4))
        })
        for (task in project.tasks) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val check = TextView(this).apply {
                text = if (task.done) "☑" else "☐"
                textSize = 20f
                setTextColor(Color.rgb(34, 211, 238))
                setPadding(0, 0, dp(10), 0)
                setOnClickListener {
                    store.toggleTask(project.id, task.id)
                    refresh()
                }
            }
            row.addView(check)
            val taskText = TextView(this).apply {
                text = task.title
                textSize = 14f
                setTextColor(if (task.done) Color.rgb(100, 116, 139) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(taskText)
            row.addView(smallButton("✕") {
                store.deleteTask(project.id, task.id)
                refresh()
            })
            card.addView(row)
        }
        card.addView(button("＋ Añadir tarea") { showAddTaskDialog(project) })
        return card
    }

    private fun section(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(34, 211, 238))
            setPadding(0, dp(20), 0, dp(4))
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(11, 15, 25))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 211, 238))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(165, 243, 252))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun dialogInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            hintTextColor = Color.rgb(100, 116, 139)
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
    }

    private fun showCreateProjectDialog() {
        val nameInput = dialogInput("Nombre del proyecto")
        val descInput = dialogInput("Descripción (opcional)")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(descInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Nuevo proyecto")
            .setView(container)
            .setPositiveButton("Crear") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) {
                    store.createProject(name, descInput.text.toString().trim())
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddTaskDialog(project: ProjectStore.Project) {
        val taskInput = dialogInput("Tarea en ${project.name}")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(taskInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir tarea")
            .setView(container)
            .setPositiveButton("Añadir") { _, _ ->
                val title = taskInput.text.toString().trim()
                if (title.isNotBlank()) {
                    store.addTask(project.id, title)
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
