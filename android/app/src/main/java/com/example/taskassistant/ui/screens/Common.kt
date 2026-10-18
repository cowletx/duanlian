package com.example.taskassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.ui.Screen
import com.example.taskassistant.ui.theme.AppBackground
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppOutline
import com.example.taskassistant.ui.theme.AppSurface
import com.example.taskassistant.ui.theme.AppTextPrimary

/** 底部导航栏目标。 */
enum class BottomTab(val label: String, val icon: String) {
    HOME("首页", "🏠"),
    PLANS("计划", "📋"),
    HISTORY("历史", "📊"),
    SETTINGS("设置", "⚙️"),
}

/**
 * 主页面容器：底部导航 + 可滚动内容。
 * 对应原型 .phone / .content / .bottom-nav 三层结构。
 */
@Composable
fun ScreenScaffold(
    currentTab: BottomTab?,
    onTabSelected: (BottomTab) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
        if (currentTab != null) {
            BottomNav(currentTab = currentTab, onTabSelected = onTabSelected)
        }
    }
}

@Composable
private fun BottomNav(currentTab: BottomTab, onTabSelected: (BottomTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BottomTab.entries.forEach { tab ->
            val selected = tab == currentTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = tab.icon, fontSize = 20.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    fontSize = 12.sp,
                    color = if (selected) AppGreen else Color(0xFF888888),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 页面标题 */
@Composable
fun Title(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 12.dp),
    )
}

/** 返回按钮 */
@Composable
fun BackButton(text: String = "← 返回", onClick: () -> Unit) {
    Text(
        text = text,
        color = AppGreen,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(bottom = 12.dp),
    )
}

/** 主按钮 */
/**
 * 主按钮。
 *
 * 注意参数顺序：`onClick` 必须放在最后。
 * 否则 `PrimaryButton(text = "x") { ... }` 这种「尾随 lambda」写法会把 lambda
 * 绑定到最后一个参数（`enabled: Boolean`）上，导致两个编译错误同时出现：
 *   - No value passed for parameter 'onClick'
 *   - Argument type mismatch: Function0<Unit> but Boolean was expected
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AppGreen, contentColor = Color.White),
        modifier = modifier.fillMaxWidth(),
    ) { Text(text) }
}

/**
 * 次按钮。
 *
 * 约定（所有按钮组件统一遵守）：**`onClick` 必须是最后一个参数**。
 * 这样 `SecondaryButton(text = "x") { ... }` 这种尾随 lambda 写法才正确绑定到 onClick。
 * 若 onClick 不在最后，尾随 lambda 会落到 modifier/enabled 上，编译器报：
 *   No value passed for parameter 'onClick'
 *   Argument type mismatch: Function0<Unit>, but Modifier was expected
 */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF333333),
            contentColor = AppTextPrimary,
        ),
        modifier = modifier,
    ) { Text(text) }
}

/** 警告按钮（跳过本组/覆盖等）。onClick 放最后，原因见 SecondaryButton。 */
@Composable
fun WarningButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800), contentColor = Color.White),
        modifier = modifier,
    ) { Text(text) }
}

/** 危险按钮（结束训练/删除）。onClick 放最后，原因见 SecondaryButton。 */
@Composable
fun DangerButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), contentColor = Color.White),
        modifier = modifier,
    ) { Text(text) }
}

/** 虚线风格的「添加」按钮，对应原型 .add-btn */
@Composable
fun AddDashedButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = AppGreen,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(vertical = 14.dp),
        )
    }
}

/** 水平分隔线 */
@Composable
fun DividerLine() {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppOutline)
        )
    }
}

/** 主 tab 与 Screen 的映射，供底部导航使用。 */
fun BottomTab.toScreen(): Screen = when (this) {
    BottomTab.HOME -> Screen.Home
    BottomTab.PLANS -> Screen.Plans
    BottomTab.HISTORY -> Screen.History
    BottomTab.SETTINGS -> Screen.Settings
}

/** 供页面设置固定间距。 */
@Composable
fun Gap(height: Int) {
    Spacer(Modifier.height(height.dp))
}
