package cn.huohuas001.bot.tools

object CollectionUtils {
    /**
     * 将一个Set集合分割成多个子List。
     *
     * @param set  要分割的Set集合。
     * @param size 每个子List的最大容量。
     * @return 分割后的List列表。
     */
    fun chunkSet(set: Set<String>, size: Int): List<List<String>> {
        return set.toList().chunked(size)
    }

    /**
     * 在Set中搜索包含关键词的元素。
     *
     * @param set     要搜索的Set集合。
     * @param keyword 搜索关键词。
     * @return 包含关键词的元素列表。
     */
    fun searchInSet(set: Set<String>, keyword: String): List<String> {
        return set.filter { it.contains(keyword) }
    }
}
