package nl.tippie.cloudhub.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/* -------------------------------------------------------------------------
 * Timings. Named because they are referred to from several places, and
 * because "how long does the success mark hold" is a question worth being
 * able to answer by reading one line.
 * ---------------------------------------------------------------------- */

private const val ENTRANCE_MS = 620
private const val STAGGER_MS = 70
private const val SUCCESS_HOLD_MS = 450L
private const val SHAKE_MS = 420

/**
 * Sign in.
 *
 * The screen renders [SignInUiState] and owns no authentication logic; the
 * rules live in [SignInViewModel] where they can be tested without a device.
 *
 * There is no sign-up, password reset or social sign-in here because CloudHub
 * has none: accounts are made on the server with tools/create-admin.php, and
 * the only credential the API accepts is a username and password.
 */
@Composable
fun SignInScreen(
    model: SignInViewModel,
    serverUrl: String,
    rememberedUsername: String?,
    onSignedIn: (username: String?, remember: Boolean) -> Unit,
    onChangeServer: () -> Unit,
) {
    val state by model.state.collectAsState()
    val reduceMotion = LocalReduceMotion.current

    var username by remember { mutableStateOf(rememberedUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(rememberedUsername != null) }
    var revealed by remember { mutableStateOf(false) }

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val busy = state is SignInUiState.Submitting || state is SignInUiState.Success
    val error = when (val s = state) {
        is SignInUiState.Idle -> s.error
        is SignInUiState.Failed -> s.error
        else -> null
    }

    fun submit() {
        keyboard?.hide()
        model.submit(username, password)
    }

    /* ---- entrance ------------------------------------------------------
     * One driver for the whole stagger. Seven coroutines would animate the
     * same 620ms seven times over for no visible difference.
     */
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            entrance.animateTo(1f, tween(ENTRANCE_MS + STAGGER_MS * 6, easing = FastOutSlowInEasing))
        }
    }

    /* ---- the shake, and what follows each terminal state ---------------- */
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        when (state) {
            is SignInUiState.Failed -> {
                if (!reduceMotion) {
                    shake.animateTo(
                        0f,
                        keyframes {
                            durationMillis = SHAKE_MS
                            0f at 0; -14f at 60; 12f at 130
                            -8f at 200; 5f at 270; -2f at 340; 0f at SHAKE_MS
                        },
                    )
                }
                model.failureShown()
            }
            is SignInUiState.Success -> {
                // Long enough for the check mark to land, short enough that it
                // does not feel like the app has stalled.
                delay(SUCCESS_HOLD_MS)
                onSignedIn(username.trim(), remember)
            }
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AuroraBackground(Modifier.fillMaxSize())

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            // A form stretched across a tablet is unreadable; past this width
            // the card stops growing and stays a card.
            val cardWidth = if (maxWidth < 420.dp) Modifier.fillMaxWidth() else Modifier.width(400.dp)

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Staggered(entrance, step = 0) {
                    BrandMark(Modifier.size(72.dp))
                }
                Spacer(Modifier.height(20.dp))

                Staggered(entrance, step = 1) {
                    Text(
                        "CloudHub",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    )
                }
                Spacer(Modifier.height(6.dp))

                Staggered(entrance, step = 2) {
                    Text(
                        // The one line on this screen carrying information:
                        // which server you are about to hand a password to.
                        serverUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(28.dp))

                Staggered(entrance, step = 3, modifier = cardWidth) {
                    GlassCard(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationX = shake.value }
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            AnimatedField(
                                value = username,
                                onValueChange = { username = it; model.editing() },
                                label = "Username",
                                enabled = !busy,
                                isError = error?.field == SignInError.Field.USERNAME,
                                keyboardOptions = KeyboardOptions(
                                    // Usernames, not email addresses: an email
                                    // keyboard and autocapitalisation both make
                                    // this field harder to type into correctly.
                                    keyboardType = KeyboardType.Text,
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    imeAction = ImeAction.Next,
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focus.moveFocus(FocusDirection.Down) },
                                ),
                            )
                            Spacer(Modifier.height(14.dp))

                            AnimatedField(
                                value = password,
                                onValueChange = { password = it; model.editing() },
                                label = "Password",
                                enabled = !busy,
                                isError = error?.field == SignInError.Field.PASSWORD,
                                visualTransformation =
                                    if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(onGo = { submit() }),
                                trailing = {
                                    IconButton(onClick = { revealed = !revealed }, enabled = !busy) {
                                        Icon(
                                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (revealed) "Hide password" else "Show password",
                                        )
                                    }
                                },
                            )

                            // Errors grow into place rather than shoving the
                            // button down a frame after the tap.
                            AnimatedVisibility(
                                visible = error != null,
                                enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                                exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
                            ) {
                                Text(
                                    error?.message.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }

                            Spacer(Modifier.height(6.dp))
                            RememberRow(
                                checked = remember,
                                enabled = !busy,
                                onChange = { remember = it },
                            )
                            Spacer(Modifier.height(16.dp))

                            SubmitButton(state = state, onClick = ::submit)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Staggered(entrance, step = 4) {
                    // Not an account affordance: the only way back to the
                    // server address, without which a moved server means
                    // clearing app data to get in again.
                    TextButton(onClick = onChangeServer, enabled = !busy) {
                        Text(
                            "Use a different server",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/* ---- the background ------------------------------------------------------ */

/**
 * Two soft colour fields drifting behind everything else.
 *
 * One Canvas and two floats: the softness is the radial gradient's own falloff
 * rather than a blur, because RenderEffect blur is API 31+ and costs a full
 * offscreen pass every frame for an effect nobody would be able to name.
 */
@Composable
private fun AuroraBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current

    val drift: Float
    val sway: Float
    if (reduceMotion) {
        drift = 0.5f
        sway = 0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "aurora")
        // Two periods that do not divide into each other, so the pair does not
        // visibly return to the same arrangement.
        drift = transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "drift",
        ).value
        sway = transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "sway",
        ).value
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            Brush.verticalGradient(
                listOf(
                    scheme.primary.copy(alpha = 0.10f),
                    scheme.background.copy(alpha = 0f),
                    scheme.secondary.copy(alpha = 0.08f),
                ),
            ),
        )

        blob(
            centre = Offset(w * (0.18f + 0.20f * drift), h * (0.16f + 0.08f * sway)),
            radius = w * 0.85f,
            colour = scheme.primary.copy(alpha = 0.22f),
        )
        blob(
            centre = Offset(w * (0.88f - 0.22f * sway), h * (0.80f - 0.10f * drift)),
            radius = w * 0.80f,
            colour = scheme.secondary.copy(alpha = 0.18f),
        )
    }
}

private fun DrawScope.blob(
    centre: Offset,
    radius: Float,
    colour: Color,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colour, colour.copy(alpha = 0f)),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/* ---- the card ------------------------------------------------------------ */

/**
 * Glass: a translucent surface over the live gradient, with a hairline edge.
 *
 * Not a blurred screenshot -- the gradient moves, so a captured blur would
 * either be stale or cost a readback every frame.
 */
@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        content = content,
    )
}

/* ---- the mark ------------------------------------------------------------ */

/* ---- pieces of the form -------------------------------------------------- */

/** Fade and rise, offset by [step] so the parts arrive in order. */
@Composable
private fun Staggered(
    driver: Animatable<Float, *>,
    step: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val total = ENTRANCE_MS + STAGGER_MS * 6
    val start = (STAGGER_MS * step).toFloat() / total
    val span = ENTRANCE_MS.toFloat() / total
    val progress = ((driver.value - start) / span).coerceIn(0f, 1f)

    Box(
        modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 16.dp.toPx()
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * An outlined field whose border animates on focus and on error.
 *
 * Material3 already animates the label, so that is left alone rather than
 * rebuilt slightly differently.
 */
@Composable
private fun AnimatedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    isError: Boolean,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val border by animateColorAsState(
        when {
            isError -> MaterialTheme.colorScheme.error
            focused -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        tween(220),
        label = "border",
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        interactionSource = interaction,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailing,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = border,
            unfocusedBorderColor = border,
            errorBorderColor = border,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Remember the username -- and only the username.
 *
 * Not "keep me signed in": the session cookie already survives a restart, so a
 * box promising that would be describing something that happens either way.
 */
@Composable
private fun RememberRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The row is the touch target: a 20dp checkbox is not one.
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChange,
            ),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,   // the whole row is the target
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Remember my username",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The button, through its three states.
 *
 * The label, the spinner and the check mark cross-fade in place, so the button
 * never changes size and the layout never jumps mid-request.
 */
@Composable
private fun SubmitButton(state: SignInUiState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current

    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.97f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "press",
    )
    Button(
        onClick = onClick,
        // Enabled through the whole request so the disabled grey never flashes;
        // a second press is refused by the view model, not by the widget.
        enabled = true,
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        AnimatedContent(
            targetState = state::class,
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.86f))
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "submit",
        ) { kind ->
            when (kind) {
                SignInUiState.Submitting::class -> CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                SignInUiState.Success::class -> Icon(
                    Icons.Default.Check, "Signed in", Modifier.size(24.dp),
                )
                else -> Text("Sign in", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * While the stored session is being checked.
 *
 * Shown instead of the sign-in form, so a launch that is already signed in
 * does not flash a half-animated login screen on its way to the files.
 */
@Composable
fun RestoringScreen() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        AuroraBackground(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(
                Modifier.size(24.dp).alpha(0.7f),
                strokeWidth = 2.dp,
            )
        }
    }
}
