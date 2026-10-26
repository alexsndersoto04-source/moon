package com.moonshare

import androidx.test.core.app.ApplicationProvider
import com.moonshare.core.CrashReport
import com.moonshare.transfer.EnginePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prueba de humo del arranque, en la JVM (Robolectric) con el SDK 29 simulado: el
 * mismo nivel de API del teléfono donde la app se cierra sola.
 *
 * Por que existe: `assembleDebug` estaba en verde y la app igual moria al abrir. El
 * build valida tipos y recursos, no ejecuta nada. Acá si el fallo es de logica
 * (clase que no carga, subsistema que revienta, `lateinit` sin iniciar, tema mal
 * resuelto) aparece con el stack completo, sin necesidad de un telefono.
 * Si esto pasa en CI y en el Redmi no, el problema es de GPU/ROM y no del codigo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], qualifiers = "w411dp-h891dp-xhdpi")
class StartupSmokeTest {

    @Test
    fun elApplicationCargaYElMotorSeArma() {
        val app = ApplicationProvider.getApplicationContext<MoonApplication>()
        assertTrue(
            "el manifest no apunta a MoonApplication (es ${app.javaClass.simpleName})",
            app is MoonApplication,
        )
        assertTrue(
            "TransferEngine no se pudo crear. Avisos del arranque: " +
                app.bootWarnings().joinToString(" || "),
            app.engineHealthy,
        )
        assertNotNull("engine sin inicializar", app.engineOrNull())
        assertEquals(
            "el motor deberia estar Idle al arrancar",
            EnginePhase.Idle,
            app.engineOrNull()!!.state.value.phase,
        )
    }

    @Test
    fun elArranqueNoDejaAvisos() {
        val app = ApplicationProvider.getApplicationContext<MoonApplication>()
        val warns = app.bootWarnings()
        assertTrue(
            "subsistemas caidos en el arranque:\n" + warns.joinToString("\n"),
            warns.isEmpty(),
        )
    }

    @Test
    fun laActivitySeCreaYNoSeCierraSola() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull("no se pudo crear MainActivity", activity)
        // La primera composicion y el post { FIRST_FRAME } viven en la cola del looper.
        shadowOf(android.os.Looper.getMainLooper()).runToEndOfTasks()
        assertTrue(
            "MainActivity se cerro apenas creada (isFinishing=true): sintoma reportado",
            !activity.isFinishing,
        )
        assertTrue(
            "quedo un crash pendiente de este arranque:\n" + CrashReport.pendingText(activity),
            !CrashReport.hasPending(activity),
        )
        // La fase alcanzada se imprime, no se afirma: si el reloj de Compose no avanza
        // en la JVM, FIRST_FRAME/READY pueden no llegar y eso no es un bug de la app.
        println("boot phase alcanzada: " + CrashReport.lastPhase(activity))
    }
}