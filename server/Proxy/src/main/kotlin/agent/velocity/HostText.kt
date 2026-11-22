package agent.velocity

/** 宿主 Component 类型来自真实 sendMessage 参数，避免单 jar 重定位造成 NoSuchMethodError。 */
object HostText {
    fun send(target: Any, text: String) {
        val send=target.javaClass.methods.first { it.name=="sendMessage" && it.parameterCount==1 && it.parameterTypes[0].name.endsWith(".Component") }
        val type=send.parameterTypes[0]
        val component=type.getMethod("text",String::class.java).invoke(null,text)
        send.isAccessible=true;send.invoke(target,component)
    }
}
