package gg.modl.build

enum class JavaStrictness(val bugChecked: Boolean, val nullChecked: Boolean) {
    NULL_CHECKED(bugChecked = true, nullChecked = true),
    BUG_CHECKED(bugChecked = true, nullChecked = false),
    EXEMPT(bugChecked = false, nullChecked = false),
}
