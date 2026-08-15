# /clear 和 /execute if items 命令 SKILL

## /clear 语法

    /clear [<目标>] [<item_predicate>] [<最大数量>]

- `<目标>`：选择器或玩家名
- `<item_predicate>`：`item_id[component测试]`（不是 item_stack 格式，是测试格式）
- `<最大数量>`：可选，0 = 检测（不清除，返回数量）

## /clear 示例

- /clear — 清空全部背包
- /clear @a — 清空所有人背包
- /clear @a #minecraft:wool — 清除所有羊毛（使用标签）
- /clear @p minecraft:golden_sword 0 — 检测是否有金剑
- /clear @p minecraft:stone 64 — 清除最多 64 个石头

## /execute if items 语法（1.21.2+）

    /execute if items block <位置> <slot范围> <item_predicate>
    /execute if items entity <目标> <slot范围> <item_predicate>

slot 范围类型：
- weapon.mainhand, weapon.offhand
- armor.head, armor.chest, armor.legs, armor.feet, armor.*
- container.0..26（容器槽位范围）
- hotbar.0..8
- inventory.0..26

## /execute if items 示例

- /execute if items entity @s weapon.mainhand minecraft:diamond_sword
- /execute if items block 0 64 0 container.0..26 minecraft:stone
- /execute unless items entity @p armor.* minecraft:netherite_helmet run give @p diamond
- /execute if items entity @s armor.* minecraft:netherite_* run say 穿着下界合金套

## 注意

- /clear 使用 item_predicate（测试格式），不是 item_stack（赋值格式）
- item_predicate 中的组件值用于匹配测试，不需要完整值
- /execute if items 是 Java Edition 1.21.2+ 新增的子命令
- 槽位范围使用 .. 语法，如 container.0..26 表示所有容器槽位
