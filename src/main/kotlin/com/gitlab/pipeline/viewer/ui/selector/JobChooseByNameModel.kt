package com.gitlab.pipeline.viewer.ui.selector

import com.gitlab.pipeline.viewer.model.JobInfo
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Job 选择器的 [com.intellij.ide.util.gotoByName.ChooseByNameModel] 实现。
 *
 * **为什么直接 implements ChooseByNameModel 而不 extends SimpleChooseByNameModel：**
 * `SimpleChooseByNameModel` 是 `abstract class`，它把 `ChooseByNameModel.getElementName`
 * 留给子类实现（自己只实现 `getFullName`），导致 Kotlin 编译时把 `getElementName` 视作
 * 子类必实现的 abstract 方法，而 `getFullName` 又因为父类已实现而不能 override（javap
 * 不显示 `@Override` 标记让 Kotlin 难以判断）。直接 implements 接口更可控。
 *
 * 设计要点：
 * - **names = job.name**（用名字匹配，搜索"build"就能匹配到 "build-and-deploy"）
 * - **getElementsByName 内部按 name 扫描 map**：同名 Job 全部返回（一般 1 个，CI 偶有同 stage 同名）
 * - **map 用 ConcurrentHashMap**：后台 [setJobs] / [replaceJob] 与 EDT 读 [getNames] 并发安全
 * - **顺序保持**：[orderedIds]（CopyOnWriteArrayList）保证 names 列表顺序与 [setJobs] 调用顺序一致
 */
class JobChooseByNameModel(private val project: Project?) : ChooseByNameModel {

    private val jobsById: MutableMap<Long, JobInfo> = ConcurrentHashMap()
    private val orderedIds: MutableList<Long> = CopyOnWriteArrayList()

    // ---------------------------------------------------------------- ChooseByNameModel

    override fun getPromptText(): String = "输入 Job 名搜索（支持中段匹配）"

    override fun getNotInMessage(): String = "No model"

    override fun getNotFoundMessage(): String = "无匹配 Job"

    override fun getCheckBoxName(): String? = null

    override fun loadInitialCheckBoxState(): Boolean = false

    override fun saveInitialCheckBoxState(state: Boolean) {}

    override fun getListCellRenderer(): javax.swing.ListCellRenderer<*> = jobRenderer()

    override fun getNames(checkBox: Boolean): Array<String> {
        return orderedIds
            .mapNotNull { jobsById[it]?.name }
            .toTypedArray()
    }

    override fun getElementsByName(name: String, checkBox: Boolean, pattern: String): Array<Any> {
        // pattern 由 ChooseByNamePopup 已用于过滤 names；这里只做 name 精确匹配
        return orderedIds
            .mapNotNull { jobsById[it] }
            .filter { it.name == name }
            .toTypedArray()
    }

    /**
     * 参数 @NotNull → Kotlin 非空 Any；返回 @Nullable → Kotlin 可空 String?
     * （JSR-305 注解在 Kotlin 侧被映射为可空性，签名必须严格匹配才能被识别为 override）
     */
    override fun getElementName(element: Any): String? {
        return (element as? JobInfo)?.name
    }

    override fun getSeparators(): Array<String> = emptyArray()

    override fun getFullName(element: Any): String? {
        val job = element as? JobInfo ?: return null
        return "${job.name} (${job.status})"
    }

    override fun getHelpId(): String? = null

    override fun willOpenEditor(): Boolean = false

    override fun useMiddleMatching(): Boolean = true

    // ---------------------------------------------------------------- 对外业务方法

    /**
     * 替换整个 Job 列表。保持当前选中（按 id 匹配）的位置不变。
     */
    fun setJobs(jobs: List<JobInfo>) {
        jobsById.clear()
        orderedIds.clear()
        for (job in jobs) {
            if (job != null) {
                jobsById[job.id] = job
                orderedIds.add(job.id)
            }
        }
    }

    /**
     * 用最新实体替换 map 中对应 id 的 Job（保持顺序位置不变）。
     * 当前 map 中没此 id 时静默 no-op。
     */
    fun replaceJob(fresh: JobInfo) {
        if (jobsById.containsKey(fresh.id)) {
            jobsById[fresh.id] = fresh
        } else {
            jobsById[fresh.id] = fresh
            orderedIds.add(fresh.id)
        }
    }

    /**
     * 读取 id 对应 Job（用于 [com.gitlab.pipeline.viewer.ui.JobSelector] 程序化选中）
     */
    fun getJobById(id: Long): JobInfo? = jobsById[id]

    /**
     * 当前 map 中所有 Job（按 [setJobs] 顺序），用于 listModels 同步等场景
     */
    fun allJobs(): List<JobInfo> = orderedIds.mapNotNull { jobsById[it] }
}
