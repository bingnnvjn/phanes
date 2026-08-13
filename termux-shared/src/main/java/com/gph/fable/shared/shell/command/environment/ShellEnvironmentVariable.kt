package com.gph.fable.shared.shell.command.environment

class ShellEnvironmentVariable(
    @JvmField var name: String,
    @JvmField var value: String?,
    @JvmField var escaped: Boolean = false
) : Comparable<ShellEnvironmentVariable> {
    override fun compareTo(other: ShellEnvironmentVariable): Int = name.compareTo(other.name)
}
