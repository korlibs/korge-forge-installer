package korge

import kotlinx.coroutines.*
import kotlin.coroutines.*

class TasksHolder {
    private val tasksToContext = LinkedHashMap<Task, TaskContext>()
    fun getTotalTasks(): Int = synchronized(this) { tasksToContext.size }
    fun getTaskContext(task: Task): TaskContext = synchronized(this) { tasksToContext.getOrPut(task) { TaskContext(task, this) } }
    fun getAllTasks(): List<TaskContext> = synchronized(this) { tasksToContext.map { it.value } }
    fun getActiveTasks(): List<TaskContext> = synchronized(this) { tasksToContext.map { it.value }.filter { it.running } }
}

class TaskContext(val task: Task, val holder: TasksHolder) {
    val completed = CompletableDeferred<Unit>()
    val dependencies by lazy { task.dependencies.map { holder.getTaskContext(it) } }

    var running = false
    var executing = false
    var ratio: Double = 0.0

    override fun toString(): String = if (running) "${task.name}(${(ratio * 100).toInt()}%)" else "${task.name}(waiting...)"

    fun report(desc: String) {
    }

    fun report(range: LongRange) {
        ratio = range.first.toDouble() / range.last.toDouble()
    }

    suspend fun executeOnce() {
        if (executing) {
            completed.await()
        } else {
            executing = true
            try {
                ensureDependencies()
                running = true
                execute()
            } finally {
                running = false
                executing = false
            }
        }
    }

    private suspend fun ensureDependencies() {
        dependencies
            .map { it2 -> CoroutineScope(coroutineContext).launch { it2.executeOnce() } }
            .joinAll()
    }

    private suspend fun execute() {
        withContext(Dispatchers.IO) {
            completed.completeWith(kotlin.runCatching { task.execute(this@TaskContext) })
        }
    }
}

abstract class Task(val name: String, vararg val dependencies: Task) {
    abstract suspend fun execute(context: TaskContext)
    override fun toString(): String = "${this::class.simpleName}"
}

object TaskExecuter {
    //private fun computeTree(task: Task, out: MutableList<Task> = arrayListOf<Task>(), visited: LinkedHashSet<Task> = LinkedHashSet()): List<Task> {
    //    if (task !in visited) {
    //        visited += task
    //        for (dep in task.dependencies) computeTree(dep, out, visited)
    //        out += task
    //    }
    //    return out
    //}

    suspend fun execute(task: Task, report: (tasks: List<TaskContext>) -> Unit = {
        print("${System.currentTimeMillis()}[${it.size}]: $it\r")
    }) {
        println("Executing $task...")
        val tasks = TasksHolder()
        CoroutineScope(coroutineContext).launch {
            tasks.getTaskContext(task).executeOnce()
        }
        while (true) {
            val totalTasks = tasks.getTotalTasks()
            val activeTasks = tasks.getActiveTasks()
            //val allTasks = tasks.getAllTasks()
            report(activeTasks)
            if (totalTasks > 0 && activeTasks.isEmpty()) break
            delay(100L)
        }
    }
}