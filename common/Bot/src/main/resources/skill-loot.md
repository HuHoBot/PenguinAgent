# /loot 命令 SKILL

## 语法

    /loot <目标> <来源>

### 目标

- /loot give <玩家> — 给玩家物品
- /loot insert <位置> — 插入容器
- /loot spawn <位置> — 在世界中掉落
- /loot replace block <位置> <slot> [数量] — 替换容器槽位
- /loot replace entity <实体> <slot> [数量] — 替换实体槽位

### 来源

- /loot loot <loot_table> — 直接发放战利品表
- /loot fish <loot_table> <位置> [<tool>|mainhand|offhand] — 钓鱼
- /loot kill <目标> — 击杀掉落
- /loot mine <位置> [<tool>|mainhand|offhand] — 挖掘掉落

## 示例

- /loot give @s loot minecraft:blocks/dirt
- /loot spawn ~ ~ ~ mine ~ ~ ~ mainhand
- /loot replace entity @s weapon.mainhand kill @e[type=zombie,limit=1]
- /loot give @s fish minecraft:entities/cod ~ ~ ~ fishing_rod
- /loot give @s loot minecraft:entities/cow
- /loot replace block ~ ~ ~ container.0 loot minecraft:blocks/diamond_ore

## tool 参数

mine 来源的 tool 参数使用 item_stack 格式：

- /loot mine ~ ~ ~ minecraft:iron_pickaxe[minecraft:enchantments={"minecraft:fortune":3}]
- /loot mine ~ ~ ~ mainhand

## 注意

- loot_table 是资源位置，如 minecraft:blocks/dirt、minecraft:entities/cow
- /loot 是 Java Edition 独有命令
- tool 参数可以是 mainhand、offhand 或 item_stack
