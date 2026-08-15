# /give 命令 SKILL

## 语法

    /give <目标> <item_stack> [数量]

- `<目标>`：选择器 @a（所有人）、@p（最近）、@s（自己）、@r（随机）或玩家名
- `<item_stack>`：`item_id[组件1=值,组件2=值,...]`（详见 skill-components.md）
- `<数量>`：可选，默认 1

## 示例

- /give @a minecraft:diamond_sword 1
- /give @p minecraft:diamond_sword[minecraft:enchantments={"minecraft:sharpness":10}] 1
- /give @p minecraft:diamond_sword[minecraft:custom_name={text:"钻石剑",color:"gold"},minecraft:enchantments={"minecraft:sharpness":10,"minecraft:unbreaking":3},minecraft:unbreakable={}]
- /give @a minecraft:netherite_sword[minecraft:custom_name={text:"屠龙刀",color:"red",bold:true},minecraft:enchantments={"minecraft:sharpness":10,"minecraft:fire_aspect":2,"minecraft:looting":3,"minecraft:unbreaking":3,"minecraft:sweeping_edge":3},minecraft:attribute_modifiers=[{type:"minecraft:attack_damage",slot:"mainhand",amount:43,operation:"add_value"}],minecraft:unbreakable={}]
- /give @p minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:mending":1}]
- /give @a minecraft:leather_chestplate[minecraft:dyed_color={rgb:16711680},minecraft:enchantments={"minecraft:protection":4},minecraft:unbreakable={}]

## 注意

- 禁止写 /give @a diamond_sword{...}（旧 NBT 语法，会执行失败）
- 生成物品命令前，必须先加载 skill-components.md 了解正确的组件格式
- 附魔 key 必须加引号：{"minecraft:sharpness":5} 不是 {minecraft:sharpness:5}
- 属性 type 在 1.21.2+ 去掉 generic. 前缀：minecraft:attack_damage 不是 minecraft:generic.attack_damage
