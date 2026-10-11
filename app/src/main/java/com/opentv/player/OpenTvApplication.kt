package com.opentv.player

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

/**
 * 自定义 Application。
 *
 * Android 4.4（API 19）及以下不支持原生多 dex，本工程方法数超过 65536，
 * 必须在 Application.attachBaseContext 中调用 [MultiDex.install]，
 * 否则 4.4 设备上启动会抛 `ClassNotFoundException: Didn't find class ... on path: DexPathList`。
 *
 * API 21+ 由系统原生支持 multidex，此调用无副作用（内部自带版本判断）。
 */
class OpenTvApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}
