package io.github.clement83.sillage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import io.github.clement83.sillage.AppContainer
import io.github.clement83.sillage.SillageApp

@Composable
@ReadOnlyComposable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as SillageApp).container
