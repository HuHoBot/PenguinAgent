# /item 命令 SKILL

## 语法

    /item replace (block <pos>|entity <targets>) <slot> with <item_stack> [<count>]
    /item modify (block <pos>|entity <targets>) <slot> <modifier>

- `<slot>` 类型：
  - 方块容器：container.0, container.1, ... container.26
  - 实体物品栏：hotbar.0..8, inventory.0..26
  - 实体装备：weapon.mainhand, weapon.offhand, armor.head, armor.chest, armor.legs, armor.feet, body, saddle
- `<item_stack>`：`item_id[组件1=值,组件2=值,...]`（详见 skill-components.md）
- `<modifier>`：战利品修改器（资源位置或内联 SNBT）

## 示例

- /item replace entity @s weapon.mainhand with minecraft:netherite_sword[minecraft:enchantments={"minecraft:sharpness":5}]
- /item replace block ~ ~ ~ container.0 with minecraft:diamond 64
- /item replace entity @s armor.head with minecraft:netherite_helmet[minecraft:enchantments={"minecraft:protection":4},minecraft:unbreakable={}]
- /item replace entity @s armor.chest with minecraft:netherite_chestplate[minecraft:enchantments={"minecraft:protection":4,"minecraft:thorns":3},minecraft:unbreakable={}]
- /item replace entity @s armor.legs with minecraft:netherite_leggings[minecraft:enchantments={"minecraft:protection":4},minecraft:unbreakable={}]
- /item replace entity @s armor.feet with minecraft:netherite_boots[minecraft:enchantments={"minecraft:protection":4,"minecraft:feather_falling":4},minecraft:unbreakable={}]
- /item replace block ~ ~ ~ container.0 with minecraft:written_book[minecraft:written_book_content={title:"指南",author:"管理员",pages:['[{"text":"第一页内容"}]']}]

## 注意

- /item 是 Java Edition 1.17+ 替代 /replaceitem 的命令
- 生成物品命令前，必须先加载 skill-components.md 了解正确的组件格式
