package com.example.taskassistant.workout

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 通知按钮 → ViewModel 的事件总线。
 *
 * 为什么需要它：
 *   通知上的操作按钮只能通过 PendingIntent 触发，而真正的训练状态
 *   （WorkoutSession、当前实际次数、指针）只存在于 MainViewModel 里。
 *   前台服务 / BroadcastReceiver 拿不到 ViewModel，所以用这个进程内单例做中转：
 *
 *       通知按钮 → PendingIntent(broadcast) → WorkoutActionReceiver
 *                                              ↓ emit
 *                                        WorkoutActionBus ── collect ──→ MainViewModel
 *
 * 前提：服务和界面在同一个进程（本项目如此），且通知存在时进程必然活着。
 * 如果进程被回收，通知也随前台服务一起消失，不会出现"点了没反应"的悬空状态。
 *
 * 用 SharedFlow 而不是 LiveData：不需要生命周期感知的重放，只要"当前这一刻的操作"，
 * 且 ViewModel 用 viewModelScope 收集，天然在主线程回调。
 */
object WorkoutActionBus {

    /** +1 次：实际次数加一（对应界面上的「+1」按钮）。 */
    const val ADD_REP = "com.example.taskassistant.action.ADD_REP"

    /** 完成本组：等价于界面上的两阶段主按钮（开始本组 / 完成本组）。 */
    const val COMPLETE_SET = "com.example.taskassistant.action.COMPLETE_SET"

    /** 结束训练：等价于界面上的「结束并保存」。 */
    const val FINISH = "com.example.taskassistant.action.FINISH_WORKOUT"

    private val _actions = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val actions: SharedFlow<String> = _actions

    fun emit(action: String) {
        _actions.tryEmit(action)
    }
}
