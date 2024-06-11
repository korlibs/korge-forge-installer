package korge.composable

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.coroutines.*

class NodeApplier(val container: Container) : AbstractApplier<Component>(container) {
    init {
        //println("NodeApplier for container=$container")
    }

    var id = 0

    val currentContainer get() = current as? Container

    override fun insertTopDown(index: Int, instance: Component) {
        val cont = currentContainer ?: return
        instance.name = "${id++}"
        //println("insertTopDown[$index]: $instance")
        cont.add(instance, cont.componentCount - 1 - index)
        cont.doUpdate()
    }

    override fun insertBottomUp(index: Int, instance: Component) {
        val cont = currentContainer ?: return
        instance.name = "${id++}"
        //println("insertBottomUp[$index]: $instance")
        cont.add(instance, index)
        cont.doUpdate()
    }

    override fun remove(index: Int, count: Int) {
        val cont = currentContainer ?: return
        //println("remove[$index]..$count")
        repeat(count) { cont.remove(index) }
        cont.doUpdate()
    }

    override fun move(from: Int, to: Int, count: Int) {
        val cont = currentContainer ?: return
        //println("move: $from -> $to [$count]")
        val components = (0 until count).map { cont.getComponent(from + it) }

        for (n in 0 until count) {
            cont.add(components[n], to + n)
        }
        cont.doUpdate()
    }

    override fun onClear() {
        val cont = currentContainer ?: return
        //println("onClear")
        cont.removeAll()
        cont.doUpdate()
    }

    private fun Component.doUpdate() {
        revalidate()
        repaint()
    }
}

var reasonToAllowFrameClosing: String? = null

fun ComposeJFrame(title: String, size: Dimension = Dimension(640, 400), content: @Composable () -> Unit) {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (ex: Throwable) {
        ex.printStackTrace()
    }

    val frame = JFrame(title)

    setComposeContent(frame.contentPane, content)

    frame.contentPane.preferredSize = size

    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent?) {
            if (reasonToAllowFrameClosing != null) {
                val dialogResult = JOptionPane.showConfirmDialog(null, reasonToAllowFrameClosing!!, "Are you sure to quit?", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                if (dialogResult != JOptionPane.YES_OPTION) return
            }
            frame.isVisible = false
            System.exit(0)
        }
    })

    //frame.layout = FlowLayout(FlowLayout.LEFT)
    //frame.contentPane.add(JLabel(ImageIcon(image)))

    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.isVisible = true

    frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE

}

fun setComposeContent(
    root: Container,
    content: @Composable () -> Unit
): Composition {
    val context = MonotonicClockImpl()

    val snapshotManager = GlobalSnapshotManager(Dispatchers.Main)
    snapshotManager.ensureStarted()

    val recomposer = Recomposer(context)

    CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
        //println("runRecomposeAndApplyChanges")
        recomposer.runRecomposeAndApplyChanges()
    }

    //CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
    //}

    return ControlledComposition(NodeApplier(root), recomposer).apply {
        setContent(content)
    }
}

class GlobalSnapshotManager(val dispatcher: CoroutineDispatcher) {
    private var started = false
    private var commitPending = false
    private var removeWriteObserver: (ObserverHandle)? = null

    private val scheduleScope = CoroutineScope(dispatcher + SupervisorJob())

    fun ensureStarted() {
        if (!started) {
            started = true
            removeWriteObserver = Snapshot.registerGlobalWriteObserver(globalWriteObserver)
        }
    }

    private val globalWriteObserver: (Any) -> Unit = {
        // Race, but we don't care too much if we end up with multiple calls scheduled.
        if (!commitPending) {
            commitPending = true
            schedule {
                commitPending = false
                Snapshot.sendApplyNotifications()
            }
        }
    }

    /**
     * List of deferred callbacks to run serially. Guarded by its own monitor lock.
     */
    private val scheduledCallbacks = mutableListOf<() -> Unit>()

    /**
     * Guarded by [scheduledCallbacks]'s monitor lock.
     */
    private var isSynchronizeScheduled = false

    /**
     * Synchronously executes any outstanding callbacks and brings snapshots into a
     * consistent, updated state.
     */
    private fun synchronize() {
        val callbacks = scheduledCallbacks.toList()
        scheduledCallbacks.clear()
        callbacks.forEach { it.invoke() }
        isSynchronizeScheduled = false
    }

    private fun schedule(block: () -> Unit) {
        scheduledCallbacks.add(block)
        if (!isSynchronizeScheduled) {
            isSynchronizeScheduled = true
            scheduleScope.launch { synchronize() }
        }
    }
}

class MonotonicClockImpl() : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(
        onFrame: (Long) -> R
    ): R {
        //println("MonotonicClockImpl.withFrameNanos")

        return suspendCoroutine { continuation ->
            val start = System.nanoTime()
            Timer(16) {
                val now = System.nanoTime()
                continuation.resume(onFrame(now - start))
            }.also { it.isRepeats = false }.start()
        }
    }
}

@Suppress("NONREADONLY_CALL_IN_READONLY_COMPOSABLE")
@Composable
inline fun <T : Component> ComposeComponent(
    noinline factory: () -> T,
    update: @DisallowComposableCalls Updater<T>.() -> Unit,
    content: @Composable () -> Unit
) {
    ComposeNode<T, NodeApplier>(factory, update, content)
}

@Composable inline fun <T : Component> ComposeComponent(
    noinline factory: () -> T,
    update: @DisallowComposableCalls Updater<T>.() -> Unit
) {
    ComposeNode<T, NodeApplier>(factory, update)
}
