package com.airgesture.transfer.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Proyectos, tareas, recordatorios y notas de JARVIS.
 * Persistencia local en JSON (sin servidor): todo queda en el teléfono.
 */
class ProjectStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    data class Task(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "",
        var done: Boolean = false,
        var createdAt: Long = System.currentTimeMillis()
    )

    data class Project(
        var id: String = UUID.randomUUID().toString(),
        var name: String = "",
        var description: String = "",
        var createdAt: Long = System.currentTimeMillis(),
        var tasks: MutableList<Task> = mutableListOf()
    )

    data class Reminder(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "",
        var note: String = "",
        var triggerAt: Long = 0L
    )

    data class Note(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "",
        var body: String = "",
        var createdAt: Long = System.currentTimeMillis()
    )

    // ---------------- Proyectos ----------------

    fun getProjects(): List<Project> {
        val json = sp.getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Project>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveProjects(list: List<Project>) {
        sp.edit { putString(KEY_PROJECTS, gson.toJson(list)) }
    }

    fun createProject(name: String, description: String = ""): Project {
        val clean = name.trim()
        require(clean.isNotBlank()) { "El proyecto necesita un nombre" }
        val all = getProjects().toMutableList()
        val p = Project(name = clean, description = description.trim())
        all.add(0, p)
        saveProjects(all)
        return p
    }

    fun findProject(query: String): Project? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        val all = getProjects()
        return all.firstOrNull { it.name.lowercase() == q }
            ?: all.firstOrNull { it.name.lowercase().contains(q) }
            ?: all.firstOrNull { q.contains(it.name.lowercase()) }
    }

    fun addTask(projectId: String, title: String): Task? {
        val all = getProjects().toMutableList()
        val idx = all.indexOfFirst { it.id == projectId }
        if (idx < 0) return null
        val task = Task(title = title.trim())
        if (task.title.isBlank()) return null
        all[idx].tasks.add(task)
        saveProjects(all)
        return task
    }

    fun addTaskByName(projectQuery: String, title: String): Task? {
        val p = findProject(projectQuery) ?: return null
        return addTask(p.id, title)
    }

    fun toggleTask(projectId: String, taskId: String): Boolean {
        val all = getProjects().toMutableList()
        val pi = all.indexOfFirst { it.id == projectId }
        if (pi < 0) return false
        val ti = all[pi].tasks.indexOfFirst { it.id == taskId }
        if (ti < 0) return false
        all[pi].tasks[ti].done = !all[pi].tasks[ti].done
        saveProjects(all)
        return true
    }

    fun completeTaskByTitle(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isBlank()) return false
        val all = getProjects().toMutableList()
        for (p in all) {
            val t = p.tasks.firstOrNull { !it.done && it.title.lowercase().contains(q) }
            if (t != null) {
                t.done = true
                saveProjects(all)
                return true
            }
        }
        return false
    }

    fun deleteProject(projectId: String) {
        saveProjects(getProjects().filterNot { it.id == projectId })
    }

    fun deleteTask(projectId: String, taskId: String) {
        val all = getProjects().toMutableList()
        val pi = all.indexOfFirst { it.id == projectId }
        if (pi >= 0) {
            all[pi].tasks.removeAll { it.id == taskId }
            saveProjects(all)
        }
    }

    fun summaryForAi(): String {
        val projects = getProjects()
        if (projects.isEmpty()) return "El usuario aún no tiene proyectos creados."
        val sb = StringBuilder()
        sb.append("Proyectos del usuario:\n")
        for (p in projects) {
            val pending = p.tasks.count { !it.done }
            val total = p.tasks.size
            sb.append("- ${p.name}: $pending tareas pendientes de $total")
            if (p.description.isNotBlank()) sb.append(" (${p.description})")
            sb.append("\n")
            for (t in p.tasks.take(8)) {
                sb.append("   ${if (t.done) "[x]" else "[ ]"} ${t.title}\n")
            }
        }
        val rems = getReminders()
        if (rems.isNotEmpty()) {
            sb.append("Recordatorios próximos: ")
            sb.append(rems.take(5).joinToString(", ") { it.title })
            sb.append("\n")
        }
        return sb.toString()
    }

    // ---------------- Recordatorios ----------------

    fun getReminders(): List<Reminder> {
        val json = sp.getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Reminder>>() {})
        } catch (e: Exception) {
            emptyList()
        }.sortedBy { it.triggerAt }
    }

    private fun saveReminders(list: List<Reminder>) {
        sp.edit { putString(KEY_REMINDERS, gson.toJson(list)) }
    }

    fun addReminder(title: String, triggerAt: Long, note: String = ""): Reminder {
        val all = getReminders().toMutableList()
        val r = Reminder(title = title.trim(), note = note.trim(), triggerAt = triggerAt)
        all.add(r)
        saveReminders(all)
        return r
    }

    fun deleteReminder(id: String) {
        saveReminders(getReminders().filterNot { it.id == id })
    }

    // ---------------- Notas ----------------

    fun getNotes(): List<Note> {
        val json = sp.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<Note>>() {})
        } catch (e: Exception) {
            emptyList()
        }.sortedByDescending { it.createdAt }
    }

    private fun saveNotes(list: List<Note>) {
        sp.edit { putString(KEY_NOTES, gson.toJson(list)) }
    }

    fun addNote(title: String, body: String): Note {
        val all = getNotes().toMutableList()
        val n = Note(title = title.trim().ifBlank { "Nota" }, body = body.trim())
        all.add(0, n)
        saveNotes(all)
        return n
    }

    fun deleteNote(id: String) {
        saveNotes(getNotes().filterNot { it.id == id })
    }

    companion object {
        private const val FILE = "jarvis_projects"
        private const val KEY_PROJECTS = "projects_json"
        private const val KEY_REMINDERS = "reminders_json"
        private const val KEY_NOTES = "notes_json"
    }
}
