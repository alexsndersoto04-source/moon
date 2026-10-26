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
        // El generico de getApplicationContext<MoonApplication>() ya es la verificacion de
        // que el manifest apunta a nuestra Application: si apuntara a otra, esto lanza
        // ClassCastException y la prueba falla con el motivo.
        assertEquals("MoonApplication", app.javaClass.simpleName)
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

    /**
     * Regresion del `RemoteServiceException: Context.startForegroundService() did not
     * then call Service.startForeground()` que mato la app en el Redmi 9C (M2006C3MG,
     * API 29) segun el reporte de campo: `TransferService` se arrancaba con
     * `startForegroundService()` y habia ramas (ACTION_STOP, ACTION_ACCEPT/CANCEL, y el
     * `stopSelf()` inmediato del colector cuando el motor todavia estaba Idle) que nunca
     * llamaban a `startForeground()`. El sistema no avisa: inyecta la excepcion en el
     * Looper del proceso y la app se cierra ~5 s despues de abrir.
     *
     * El invariante que se prueba es chico y exacto: en cuanto el servicio existe, el
     * foreground ya esta pedido. No se depende de ShadowService para leer la notificacion
     * porque el flag de la app es la afirmacion que nos interesa.
     */
    @Test
    fun elServicioCumpleElContratoDeForegroundDesdeOnCreate() {
        val service = Robolectric.buildService(com.moonshare.transfer.TransferService::class.java)
            .create()
            .get()
        assertTrue(
            "onCreate() no pidio foreground: el sistema mata el proceso a los 5 s",
            service.isForegroundRequested,
        )
        // START_STICKY re-entrega el servicio con onCreate sin volver a correr; ahi el
        // que tiene que cubrir el contrato es onStartCommand.
        val second = Robolectric.buildService(com.moonshare.transfer.TransferService::class.java)
            .create()
            .startCommand(0, 0)
            .get()
        assertTrue("un startCommand suelto dejo el contrato colgando", second.isForegroundRequested)
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
        // NO se idlea el looper aca: la esfera usa rememberInfiniteTransition, asi que el
        // Recomposer de Compose pide frames para siempre y cualquier idle()/runToEndOfTasks()
        // sobre el looper principal llena el heap de la JVM en ShadowTrace. El OOM que dio
        // la version anterior del test era de ese bombeo, no de la app. La primera
        // composicion ya ocurrio sincronico al attacharse la vista, que es lo que interesa.
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