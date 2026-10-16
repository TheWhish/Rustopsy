package xyz.thewhish.rustopsy.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.thewhish.rustopsy.account.AccountAccess
import xyz.thewhish.rustopsy.account.AccountStatus
import xyz.thewhish.rustopsy.core.sync.Friend
import xyz.thewhish.rustopsy.core.sync.FriendActivity
import xyz.thewhish.rustopsy.core.sync.FriendsSource
import xyz.thewhish.rustopsy.core.sync.TEAM_MAX_MEMBERS
import xyz.thewhish.rustopsy.core.sync.TeamInfo
import xyz.thewhish.rustopsy.core.sync.TeamInvite
import xyz.thewhish.rustopsy.core.sync.TeamMember
import xyz.thewhish.rustopsy.team.FriendsLoad
import xyz.thewhish.rustopsy.team.TeamAccess
import xyz.thewhish.rustopsy.team.TeamStatus
import xyz.thewhish.rustopsy.ui.components.ActionRunner
import xyz.thewhish.rustopsy.ui.components.Avatar
import xyz.thewhish.rustopsy.ui.components.rememberActionRunner
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshMotion
import xyz.thewhish.rustopsy.ui.theme.AshShapes
import xyz.thewhish.rustopsy.ui.theme.AshSpace
import xyz.thewhish.rustopsy.ui.theme.AshText

private val PANEL_WIDTH = 400.dp
private val PANEL_MAX_HEIGHT = 700.dp
private val BAR_AVATAR = 24.dp
private const val BAR_AVATARS = 4

@Composable
fun TeamButton(status: TeamStatus, open: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (open || hovered) AshColors.ash3 else AshColors.ash2, tween(AshMotion.MICRO))
    val ready = status as? TeamStatus.Ready
    val team = ready?.state?.team
    val invites = ready?.state?.invites?.size ?: 0
    Row(
        Modifier.height(36.dp).clip(AshShapes.control).background(background)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(start = if (team != null) 6.dp else 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (team != null) {
            AvatarStack(team.members)
        } else {
            PeopleIcon(AshColors.text2)
        }
        AshText(if (team != null) "Команда ${team.members.size}" else "Команда", Ash.type.bodyStrong, color = AshColors.text1)
        when {
            invites > 0 -> Box(Modifier.size(20.dp).clip(CircleShape).background(AshColors.ember), contentAlignment = Alignment.Center) {
                AshText(invites.toString(), Ash.type.caption, color = AshColors.onEmber)
            }
            ready != null && team != null && !ready.linked -> Box(Modifier.size(6.dp).clip(CircleShape).background(AshColors.warn))
        }
    }
}

@Composable
private fun AvatarStack(members: List<TeamMember>) {
    val shown = members.sortedByDescending { it.online }.take(BAR_AVATARS)
    Box(Modifier.width(BAR_AVATAR + (BAR_AVATAR - 8.dp) * (shown.size - 1)).height(BAR_AVATAR)) {
        shown.forEachIndexed { index, member ->
            Avatar(
                member.avatarUrl,
                member.name,
                BAR_AVATAR,
                Modifier.offset(x = (BAR_AVATAR - 8.dp) * index).alpha(if (member.online) 1f else 0.45f),
                ring = AshColors.ash2,
            )
        }
    }
}

@Composable
fun BoxScope.TeamOverlay(open: Boolean, onClose: () -> Unit, team: TeamAccess, account: AccountAccess, top: Dp) {
    if (open) {
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    }
    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = top),
        enter = fadeIn(tween(AshMotion.SHORT)) + slideInVertically(tween(AshMotion.MEDIUM, easing = AshMotion.standard)) { -it / 24 },
        exit = fadeOut(tween(AshMotion.SHORT)) + slideOutVertically(tween(AshMotion.SHORT, easing = AshMotion.exit)) { -it / 24 },
    ) {
        Column(
            Modifier.width(PANEL_WIDTH).heightIn(max = PANEL_MAX_HEIGHT).clip(AshShapes.panel).background(AshColors.ash1)
                .border(1.dp, AshColors.ash3, AshShapes.panel)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(AshSpace.xl),
            verticalArrangement = Arrangement.spacedBy(AshSpace.l),
        ) {
            TeamPanelContent(team, account)
        }
    }
}

@Composable
private fun ColumnScope.TeamPanelContent(team: TeamAccess, account: AccountAccess) {
    val status by team.status.collectAsState()
    val accountStatus by account.status.collectAsState()
    val runner = rememberActionRunner()
    val self = (accountStatus as? AccountStatus.SignedIn)?.profile?.steamId

    when {
        accountStatus is AccountStatus.SigningIn -> Intro("Подтверди вход в браузере", "Открылась страница Steam. После подтверждения вернись в Rustopsy") {
            SmallButton("Отмена", onClick = account.cancelSignIn)
        }
        accountStatus !is AccountStatus.SignedIn -> Intro("Играй командой", "Хиты, ноки и убийства тиммейтов по общим противникам появятся на Радаре") {
            SmallButton("Войти через Steam", primary = true, onClick = account.signIn)
        }
        else -> when (val current = status) {
            TeamStatus.SignedOut, TeamStatus.Connecting -> Intro("Подключаемся", "Связываемся с сервером Rustopsy")
            is TeamStatus.Unavailable -> Intro("Нет связи с сервером", current.problem)
            is TeamStatus.Ready -> ReadyContent(current, self, team, runner)
        }
    }
}

@Composable
private fun ReadyContent(status: TeamStatus.Ready, self: Long?, team: TeamAccess, runner: ActionRunner) {
    val info = status.state.team
    Header(info, status.linked)
    status.problem?.let { problem ->
        Row(
            Modifier.fillMaxWidth().clip(AshShapes.control).background(AshColors.badSoft).padding(horizontal = AshSpace.m, vertical = AshSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AshText(problem, Ash.type.secondary, Modifier.weight(1f), AshColors.bad, maxLines = 2)
            SmallButton("Скрыть", onClick = team.dismissProblem)
        }
    }
    if (status.state.invites.isNotEmpty()) {
        Section("Приглашения") {
            status.state.invites.forEach { invite -> InviteRow(invite, runner, team) }
            if (info != null) Hint("Приняв приглашение, ты перейдёшь в новую команду")
        }
    }
    if (info != null) {
        Section("Состав") {
            info.members.forEach { member ->
                MemberRow(member, leader = member.steamId == info.leaderId, self = member.steamId == self) {
                    if (self == info.leaderId && member.steamId != self) {
                        SmallButton("Исключить", enabled = !runner.busy) { runner.run { team.kick(member.steamId) } }
                    }
                }
            }
        }
    }
    if (info == null || info.members.size < TEAM_MAX_MEMBERS) {
        FriendsSection(info, team, runner)
        InviteByLink(team, runner)
    }
    if (info != null) LeaveRow(info, self, team, runner)
}

@Composable
private fun Header(info: TeamInfo?, linked: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AshText("Команда", Ash.type.title)
            AshText(
                if (info == null) "Позови друзей из Steam" else "${info.members.size} из $TEAM_MAX_MEMBERS, в сети ${info.members.count { it.online }}",
                Ash.type.secondary,
                color = AshColors.text3,
            )
        }
        if (info != null) {
            val color = if (linked) AshColors.ok else AshColors.warn
            Row(
                Modifier.clip(AshShapes.chip).background(if (linked) AshColors.okSoft else AshColors.warnSoft).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                AshText(if (linked) "На связи" else "Нет связи", Ash.type.caption, color = color)
            }
        }
    }
}

@Composable
private fun InviteRow(invite: TeamInvite, runner: ActionRunner, team: TeamAccess) {
    PersonRow(
        avatarUrl = invite.from.avatarUrl,
        name = invite.from.name,
        detail = "Зовёт в команду, ${invite.members} из $TEAM_MAX_MEMBERS",
    ) {
        SmallButton("Отклонить", enabled = !runner.busy) { runner.run { team.decline(invite.teamId) } }
        SmallButton("Принять", primary = true, enabled = !runner.busy) { runner.run { team.accept(invite.teamId) } }
    }
}

@Composable
private fun MemberRow(member: TeamMember, leader: Boolean, self: Boolean, action: @Composable () -> Unit) {
    val detail = listOfNotNull(
        "лидер".takeIf { leader },
        "это ты".takeIf { self },
        if (member.online) "Rustopsy открыт" else "не в сети",
    ).joinToString(", ").replaceFirstChar { it.uppercase() }
    PersonRow(member.avatarUrl, member.name, detail, online = member.online, action = action)
}

@Composable
private fun FriendsSection(info: TeamInfo?, team: TeamAccess, runner: ActionRunner) {
    var reload by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<FriendsLoad?>(null) }
    val members = info?.members?.map { it.steamId }
    LaunchedEffect(reload, members) { load = team.friends() }

    Section("Друзья в Rustopsy") {
        when (val current = load) {
            null -> Hint("Загружаем друзей из Steam")
            is FriendsLoad.Failed -> Hint(current.problem)
            is FriendsLoad.Loaded -> {
                val candidates = current.response.friends.filterNot { it.teammate }
                when {
                    current.response.source == FriendsSource.PRIVATE ->
                        Hint("Список друзей скрыт в настройках приватности Steam. Позови друга по ссылке на его профиль")
                    current.response.source == FriendsSource.UNAVAILABLE -> Hint("Steam не ответил. Позови друга по ссылке на профиль")
                    candidates.isEmpty() -> Hint("Никто из друзей ещё не входил в Rustopsy. Отправь им ссылку на программу")
                    else -> candidates.forEach { friend ->
                        FriendRow(friend) {
                            if (friend.invited) {
                                AshText("Приглашён", Ash.type.label, color = AshColors.text3)
                            } else {
                                SmallButton("Позвать", enabled = !runner.busy) {
                                    runner.run { if (team.invite(friend.steamId.toString())) reload++ }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(friend: Friend, action: @Composable () -> Unit) {
    val activity = when (friend.activity) {
        FriendActivity.PLAYING_RUST -> "Играет в Rust"
        FriendActivity.ONLINE -> "В сети Steam"
        FriendActivity.OFFLINE -> "Не в сети"
    }
    PersonRow(
        avatarUrl = friend.avatarUrl,
        name = friend.name,
        detail = if (friend.inApp) "$activity, Rustopsy открыт" else activity,
        online = friend.activity != FriendActivity.OFFLINE,
        detailColor = if (friend.activity == FriendActivity.PLAYING_RUST) AshColors.ok else AshColors.text3,
        action = action,
    )
}

@Composable
private fun InviteByLink(team: TeamAccess, runner: ActionRunner) {
    var target by remember { mutableStateOf("") }
    val submit = {
        val value = target.trim()
        if (value.isNotEmpty()) runner.run { if (team.invite(value)) target = "" }
    }
    Section("По ссылке на профиль") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AshSpace.s)) {
            BasicTextField(
                value = target,
                onValueChange = { target = it.take(MAX_LINK) },
                singleLine = true,
                textStyle = Ash.type.body.copy(color = AshColors.text1),
                cursorBrush = SolidColor(AshColors.ember),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).clip(AshShapes.control).background(AshColors.ash0)
                            .border(1.dp, AshColors.ash3, AshShapes.control).padding(horizontal = AshSpace.m),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (target.isEmpty()) AshText("steamcommunity.com/id/...", Ash.type.body, color = AshColors.text4)
                        field()
                    }
                },
            )
            SmallButton("Позвать", enabled = target.isNotBlank() && !runner.busy, onClick = submit)
        }
    }
}

@Composable
private fun LeaveRow(info: TeamInfo, self: Long?, team: TeamAccess, runner: ActionRunner) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = AshSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        AshText(
            when {
                !confirm -> "Покинуть команду"
                self == info.leaderId && info.members.size > 1 -> "Лидером станет следующий участник"
                else -> "Точно выйти?"
            },
            Ash.type.secondary,
            Modifier.weight(1f),
            AshColors.text2,
        )
        if (confirm) {
            SmallButton("Остаться") { confirm = false }
            Spacer(Modifier.width(AshSpace.s))
            SmallButton("Выйти", primary = true, enabled = !runner.busy) { runner.run(team.leave) }
        } else {
            SmallButton("Выйти") { confirm = true }
        }
    }
}

@Composable
private fun PersonRow(
    avatarUrl: String?,
    name: String,
    detail: String,
    online: Boolean? = null,
    detailColor: Color = AshColors.text3,
    action: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(avatarUrl, name, 36.dp)
            if (online != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(AshColors.ash1).padding(2.dp)
                        .clip(CircleShape).background(if (online) AshColors.ok else AshColors.ash4),
                )
            }
        }
        Spacer(Modifier.width(AshSpace.m))
        Column(Modifier.weight(1f)) {
            AshText(name, Ash.type.bodyStrong)
            AshText(detail, Ash.type.label, color = detailColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AshSpace.s), verticalAlignment = Alignment.CenterVertically) { action() }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        AshText(title, Ash.type.label, Modifier.padding(bottom = AshSpace.xs), AshColors.text3)
        content()
    }
}

@Composable
private fun Intro(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(AshSpace.s)) {
        AshText(title, Ash.type.title)
        AshText(text, Ash.type.secondary, color = AshColors.text2, maxLines = 3)
        if (action != null) Box(Modifier.padding(top = AshSpace.s)) { action() }
    }
}

@Composable
private fun Hint(text: String) {
    AshText(text, Ash.type.secondary, Modifier.padding(vertical = AshSpace.s), AshColors.text3, maxLines = 3)
}

@Composable
private fun SmallButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            !enabled -> AshColors.ash2
            primary && hovered -> AshColors.emberStrong
            primary -> AshColors.ember
            hovered -> AshColors.ash4
            else -> AshColors.ash3
        },
        tween(AshMotion.MICRO),
    )
    val base = Modifier.height(30.dp).clip(AshShapes.control).background(background)
    Box(
        (if (enabled) base.hoverable(interaction).clickable(interaction, indication = null, onClick = onClick) else base).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        AshText(
            text,
            Ash.type.label,
            color = when {
                !enabled -> AshColors.text4
                primary -> AshColors.onEmber
                else -> AshColors.text1
            },
        )
    }
}

@Composable
private fun PeopleIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.7f * unit)
        drawCircle(color, 3.5f * unit, Offset(9f * unit, 8f * unit), style = stroke)
        drawArc(color, 180f, 180f, false, Offset(2.5f * unit, 14f * unit), Size(13f * unit, 12f * unit), style = stroke)
        drawCircle(color, 2.8f * unit, Offset(17f * unit, 9f * unit), style = stroke)
        drawArc(color, 200f, 140f, false, Offset(14f * unit, 15f * unit), Size(8f * unit, 9f * unit), style = stroke)
    }
}

private const val MAX_LINK = 200
