package android.app

open class Activity : android.content.Context() {
    open fun setTheme(res: Int) {}
    // Asked before touching the UI from a delayed callback.
    val isFinishing: Boolean = false
    val isDestroyed: Boolean = false
}
