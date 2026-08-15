# Minecraft 命令速查

## 物品格式

    item_id[组件1=值,组件2=值,...]

## 文本组件

直接写 SNBT 对象，禁止用引号包裹整个 JSON：`custom_name={text:"名",color:"gold"}`

## 附魔

key 必须加引号：`enchantments={"minecraft:sharpness":10}`

## 属性

1.21.2+ 去掉 generic. 前缀：`type:"minecraft:attack_damage"` 不是 `minecraft:generic.attack_damage`

## 可用 SKILL

生成物品相关命令前，必须先调用 load_skill 加载对应 SKILL：
- components：数据组件完整参考
- give：/give 命令
- item：/item 命令
- summon：/summon 命令
- data：/data 命令
- loot：/loot 命令
- clear：/clear 和 /execute if items 命令
