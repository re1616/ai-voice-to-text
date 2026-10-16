package app.aivoicetotext.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.aivoicetotext.App
import app.aivoicetotext.ui.screens.ModelsScreen
import app.aivoicetotext.ui.screens.NoteScreen
import app.aivoicetotext.ui.screens.NotesScreen
import app.aivoicetotext.ui.screens.OnboardingScreen
import app.aivoicetotext.ui.screens.SettingsScreen
import app.aivoicetotext.ui.screens.TemplatesScreen

@Composable
fun AppNav(activity: MainActivity) {
    val app = activity.application as App
    val nav = rememberNavController()
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val openNote by activity.openNote.collectAsStateWithLifecycle()
    val start = remember { if (app.prefs.value.onboardingDone) "notes" else "onboarding" }

    LaunchedEffect(openNote) {
        val id = openNote ?: return@LaunchedEffect
        if (!settings.onboardingDone) app.prefs.update { copy(onboardingDone = true) }
        nav.navigate("note/$id") { launchSingleTop = true }
        activity.openNote.value = null
    }

    NavHost(navController = nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(app, activity) {
                app.prefs.update { copy(onboardingDone = true) }
                nav.navigate("notes") { popUpTo("onboarding") { inclusive = true } }
            }
        }
        composable("notes") {
            NotesScreen(
                app = app,
                activity = activity,
                openNote = { nav.navigate("note/$it") },
                openModels = { nav.navigate("models") },
                openSettings = { nav.navigate("settings") },
            )
        }
        composable("note/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            NoteScreen(app, activity, id, back = { nav.popBackStack() }, openModels = { nav.navigate("models") })
        }
        composable("models") { ModelsScreen(app, back = { nav.popBackStack() }) }
        composable("settings") {
            SettingsScreen(
                app, activity,
                back = { nav.popBackStack() },
                openModels = { nav.navigate("models") },
                openTemplates = { nav.navigate("templates") },
            )
        }
        composable("templates") { TemplatesScreen(app, back = { nav.popBackStack() }) }
    }
}
