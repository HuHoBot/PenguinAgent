package agent.update

/** 严格数字版本与 prerelease 比较；1.23 等同于 1.23.0，绝不隐式降级。 */
object ReleasePolicy {
    private val pattern=Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
    fun compare(a:String,b:String):Int? {
        val x=pattern.matchEntire(a.trim()) ?: return null
        val y=pattern.matchEntire(b.trim()) ?: return null
        for(i in 1..3){val l=x.groupValues[i].ifEmpty { "0" }.toLongOrNull()?:return null;val r=y.groupValues[i].ifEmpty { "0" }.toLongOrNull()?:return null;if(l!=r)return l.compareTo(r)}
        val p=x.groupValues[4];val q=y.groupValues[4]
        if(p==q)return 0
        if(p.isEmpty())return 1
        if(q.isEmpty())return -1
        val ps=p.split('.');val qs=q.split('.')
        for(i in 0 until minOf(ps.size,qs.size)){
            val u=ps[i];val v=qs[i];if(u==v)continue
            val un=u.toLongOrNull();val vn=v.toLongOrNull()
            return if(un!=null&&vn!=null)un.compareTo(vn)else if(un!=null)-1 else if(vn!=null)1 else u.compareTo(v)
        }
        return ps.size.compareTo(qs.size)
    }
    fun needs(current:String,minimum:String,target:String):Boolean =
        compare(current,minimum)?.let { it<0 }==true && compare(target,minimum)?.let { it>=0 }==true && compare(target,current)?.let { it>0 }==true
}
