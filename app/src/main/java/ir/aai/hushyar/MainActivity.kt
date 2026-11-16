package ir.aai.hushyar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.ui.AppRoot
import ir.aai.hushyar.ui.HushyarTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HushyarApp
        setContent {
            HushyarTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app.repos))
                    AppRoot(vm)
                }
            }
        }
    }
}

class AppViewModelFactory(private val repos: Repos) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(repos) as T
    }
}
