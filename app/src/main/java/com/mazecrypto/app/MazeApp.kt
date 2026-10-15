package com.mazecrypto.app

import android.app.Application
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.work.RefreshJob

class MazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Maze.init(this)
        RefreshJob.schedule(this)
    }
}
