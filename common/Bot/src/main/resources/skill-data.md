# /data 命令 SKILL

## 语法

    /data get (block <pos>|entity <目标>|storage <目标>) [<路径>] [<缩放>]
    /data merge (block <pos>|entity <目标>|storage <目标>) <nbt>
    /data modify (block <pos>|entity <目标>|storage <目标>) <目标路径> (append|insert <索引>|merge|prepend|set) (from ...|string ...|value <值>)
    /data remove (block <pos>|entity <目标>|storage <目标>) <路径>

/data 操作的是原始 NBT（SNBT），不是 item_stack 格式。

## 示例

### 读取数据

- /data get entity @s SelectedItem
- /data get entity @s Inventory
- /data get block ~ ~ ~ Items
- /data get entity @s Health

### 合并数据

- /data merge entity @e[type=zombie,limit=1] {drop_chances:{mainhand:0f,offhand:0.8f}}
- /data merge block 1 64 1 {Items:[{Slot:0b,id:"minecraft:diamond",count:64}]}

### 修改数据

- /data modify entity @s PickupDelay set value -1s
- /data modify block 1 64 1 Items[0].id set value "minecraft:diamond_block"
- /data modify entity @s CustomName set value '{"text":"自定义名字"}'
- /data modify entity @s HandItems[0] set value {id:"minecraft:netherite_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":5}}}

### 删除数据

- /data remove entity @s Inventory[0]
- /data remove block ~ ~ ~ Items[0]

## 方块容器的 Items 字段

chest、hopper、dispenser 等容器的 Items 标签格式：

    [{Slot:0b, id:"minecraft:diamond", count:64}, {Slot:1b, id:"minecraft:emerald", count:32}]

每个元素包含：Slot（字节，槽位号）、id（字符串）、count（整数）、components（可选，组件映射）。

## 注意

- /data 是 Java Edition 独有命令
- /data 操作的是原始 NBT，物品在 NBT 中的格式与 /give 不同
- 实体中的物品字段：`{id:"物品id",count:数量,components:{组件名:值}}`
- NBT 路径中数组索引从 0 开始
- 修改实体物品时，格式与 /summon 中的物品格式相同（详见 skill-summon.md）
