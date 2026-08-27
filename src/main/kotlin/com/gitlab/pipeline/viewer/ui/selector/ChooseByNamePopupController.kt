package com.gitlab.pipeline.viewer.ui.selector

import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * ChooseByNamePopup 调用薄封装：
 * - 统一 [ChooseByNamePopup.createPopup] + [ChooseByNamePopupComponent.invoke] 调用
 * - 确保在 EDT 上调用 [invoke]（[ChooseByNamePopup] 要求）
 * - 每次 [invoke] 都新建 Popup（平台约束：ChooseByNamePopup 不可复用）
 * - project 参数为 null 时兜底使用 [ProjectManager.getInstance].defaultProject，
 *   并打印 warn（不阻断弹窗）；ChooseByNamePopup 内部会调用
 *   `FindSymbolParameters.searchScopeFor(project)`，project 为 null 时会抛
 *   `IllegalArgumentException: Argument for @NotNull parameter 'project' ...`
 *
 * 用法：
 * ```
 * ChooseByNamePopupController.show(
 *     project = project,
 *     model = myModel,
 *     title = "选择 Job",
 *     onChosen = { element -> ... }
 * )
 * ```
 */
object ChooseByNamePopupController {

    private val LOG = Logger.getInstance(ChooseByNamePopupController::class.java)

    /**
     * 弹出选择器，异步返回用户选择。
     *
     * @param project     当前 IDEA 项目；若为 null，兜底为 defaultProject（仅 warn，不抛）
     * @param model       [ChooseByNameModel] 实例（Job / Project 等）
     * @param title       弹窗标题
     * @param onChosen    用户选中元素时回调（可为 null）
     * @param forceShowing 即使候选数 < 2 也强制弹（Job 选择器常用，候选=1 时仍可弹用于切换）
     * @param predefinedText 弹窗打开时输入框预填文本（用于"打开并自动过滤"场景）
     */
    fun show(
        project: Project?,
        model: ChooseByNameModel,
        title: String,
        onChosen: ((Any?) -> Unit)? = null,
        forceShowing: Boolean = true,
        predefinedText: String = ""
    ) {
        // 兜底：ChooseByNamePopup 内部需要非空 project（计算 searchScope）
        val effectiveProject = project ?: run {
            LOG.warn("ChooseByNamePopupController.show($title): project is null, fallback to ProjectManager.defaultProject")
            ProjectManager.getInstance().defaultProject
        }

        // 必须在 EDT 上调用 invoke（ChooseByNamePopup 内部未做断言）
        if (ApplicationManager.getApplication().isDispatchThread) {
            doShow(effectiveProject, model, title, onChosen, forceShowing, predefinedText)
        } else {
            ApplicationManager.getApplication().invokeLater {
                doShow(effectiveProject, model, title, onChosen, forceShowing, predefinedText)
            }
        }
    }

    private fun doShow(
        project: Project?,
        model: ChooseByNameModel,
        title: String,
        onChosen: ((Any?) -> Unit)?,
        forceShowing: Boolean,
        predefinedText: String
    ) {
        val popup = ChooseByNamePopup.createPopup(project, model, /* predefined = */ null, title)
        val callback = object : ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any?) {
                onChosen?.invoke(element)
            }

            override fun onClose() {
                // 不需要清理（每次 invoke 都新建 Popup，没有共享状态）
            }
        }
        popup.invoke(callback, ModalityState.NON_MODAL, forceShowing)
        if (predefinedText.isNotEmpty()) {
            // 弹窗打开后用预填文本触发过滤；runAfterComposition 防止弹窗还没初始化
            ApplicationManager.getApplication().invokeLater {
                runCatching { popup.textField.text = predefinedText }
            }
        }
    }
}
