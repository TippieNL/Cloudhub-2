package nl.tippie.cloudhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The pieces a settings screen is made of.
 *
 * The old screen was a flat column of text: every action a blue link of the
 * same weight, whether it opened a screen or threw ten remembered positions
 * away, and section headings shouting in the accent colour. Everything looked
 * equally important, which is the same as nothing looking important.
 *
 * These give it the shape the platform's own settings have -- rows grouped
 * into cards, a quiet heading above each, an icon to find a row by, and the
 * control on the right where the thumb is. What is destructive is red, and
 * only what is destructive.
 */

/** How a row should read: ordinary, or something you cannot undo. */
enum class RowTone { NORMAL, DANGER }

/**
 * A titled card of rows.
 *
 * The card is what makes a group a group: a heading floating over a flat list
 * relies on whitespace alone, and whitespace is the first thing to go when the
 * text is long or the display is scaled up.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/**
 * One row: an icon to find it by, a name, an optional line of explanation, and
 * whatever control it carries on the right.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    tone: RowTone = RowTone.NORMAL,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = when (tone) {
        RowTone.NORMAL -> MaterialTheme.colorScheme.primary
        RowTone.DANGER -> MaterialTheme.colorScheme.error
    }
    val titleColour = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        tone == RowTone.DANGER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            // Comfortably past the 48dp minimum: these are one-handed taps on
            // a list that is read as much as it is used.
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (tone == RowTone.DANGER) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (enabled) accent else titleColour, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColour,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A read-only value on the right of a row: an address, a size, a count. */
@Composable
fun SettingsValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A row carrying a switch.
 *
 * The whole row toggles, not only the switch: a 36dp target at the far edge of
 * the screen is the hardest thing on the page to hit, and it is the control
 * people reach for most.
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        supporting = supporting,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** The hairline between rows in a group, inset past the icons. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 66.dp),
    )
}

/**
 * Who is signed in, at the top of the screen.
 *
 * An account is a face, not a pair of table rows: the initial and the name
 * answer "am I on the right account?" in one glance, which was previously two
 * lines of small print.
 */
@Composable
fun AccountHeader(username: String, role: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                username.take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                username,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A choice of three, in one row instead of three.
 *
 * The theme was three radio buttons and 150dp of screen for a decision with
 * three answers. A segmented control says the same thing in a line, and shows
 * the alternatives rather than hiding them behind a menu.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}
