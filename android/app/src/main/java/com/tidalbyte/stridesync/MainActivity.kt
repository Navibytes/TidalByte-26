package com.tidalbyte.stridesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.tidalbyte.stridesync.ui.HomeScreen
import com.tidalbyte.stridesync.ui.StrideSyncTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrideSyncTheme {
                HomeScreen(vm)
            }
        }
    }
}
