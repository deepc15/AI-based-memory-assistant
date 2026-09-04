package com.localmind.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localmind.chat.ui.ChatScreen
import com.localmind.chat.ui.ChatViewModel
import com.localmind.chat.ui.theme.LocalMindTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalMindTheme {
                val viewModel: ChatViewModel = viewModel()
                ChatScreen(viewModel)
            }
        }
    }
}
