# Minecraft 1.20.5+ 数据组件参考（共享）

自 Minecraft 1.20.5 起，物品的旧 NBT 大括号语法已被数据组件（data components）取代。旧语法会导致命令解析/执行失败。

## 物品格式（item_stack）

    item_id[组件1=值,组件2=值,...]

- 无组件时方括号可省略：`minecraft:diamond_sword`
- 前缀 ! 移除组件：`minecraft:stick[!minecraft:enchantments]`
- 物品 id 可省略 minecraft: 前缀

## 文本组件格式（custom_name、lore 等）

命令中直接写 SNBT 对象，**禁止用引号包裹整个 JSON**：

- 正确：`custom_name={text:"神剑",color:"gold",italic:false}`
- 错误：`custom_name='{"text":"神剑","color":"gold"}'` → 会原样显示 JSON
- 纯文本：`custom_name="神剑"`

可用字段：text、color（black/dark_blue/dark_green/dark_aqua/dark_red/dark_purple/gold/gray/dark_gray/blue/green/aqua/red/light_purple/yellow/white 或 #RRGGBB）、italic、bold、obfuscated、strikethrough、underlined。

## 常用组件

### minecraft:custom_name — 自定义显示名

    minecraft:diamond_sword[minecraft:custom_name={text:"神剑",color:"gold",italic:false}]

### minecraft:item_name — 基础名，格式同 custom_name

### minecraft:lore — 描述（文本组件对象列表）

    minecraft:diamond_sword[minecraft:lore=[{text:"第一行"},{text:"第二行",color:"gray"}]]

### minecraft:enchantments — 附魔（等级映射，key 必须加引号）

    minecraft:diamond_sword[minecraft:enchantments={"minecraft:sharpness":10,"minecraft:unbreaking":3}]

**注意**：映射的 key 必须是带双引号的字符串，如 "minecraft:sharpness"，不能写成 minecraft:sharpness。

### minecraft:stored_enchantments — 附魔书存储附魔

    minecraft:enchanted_book[minecraft:stored_enchantments={"minecraft:sharpness":3}]

### minecraft:unbreakable — 不可破坏

    minecraft:diamond_sword[minecraft:unbreakable={}]

### minecraft:attribute_modifiers — 属性修改器（值是列表）

    minecraft:diamond_sword[minecraft:attribute_modifiers=[{id:"sword.atk",type:"minecraft:attack_damage",slot:"mainhand",amount:38,operation:"add_value"}]]

字段说明：
- id（可选）：修饰符唯一标识，同一物品下必须唯一
- type：属性 id（1.21.2+ 去掉 generic. 前缀），如 "minecraft:attack_damage"、"minecraft:max_health"、"minecraft:movement_speed"、"minecraft:attack_speed"、"minecraft:armor"、"minecraft:armor_toughness"、"minecraft:knockback_resistance"、"minecraft:luck"、"minecraft:scale"、"minecraft:step_height"、"minecraft:entity_interaction_range"、"minecraft:block_interaction_range"、"minecraft:block_break_speed"
- slot：any / hand / armor / mainhand / offhand / head / chest / legs / feet / body / saddle
- operation：add_value / add_multiplied_base / add_multiplied_total
- amount：数值（double）
- display（可选）：default / hidden / override

### minecraft:damage — 耐久损伤值

    minecraft:diamond_sword[minecraft:damage=500]

### minecraft:custom_model_data — 自定义模型数据

    minecraft:player_head[minecraft:custom_model_data=1001]

### minecraft:dyed_color — 皮革染色

    minecraft:leather_chestplate[minecraft:dyed_color={rgb:16711680}]

### minecraft:potion_contents — 药水效果

    minecraft:potion[minecraft:potion_contents={custom_effects:[{id:"minecraft:wither",amplifier:1,duration:3600}]}]

### minecraft:profile — 玩家头颅

    minecraft:player_head[minecraft:profile={name:"Steve"}]

### minecraft:can_break / minecraft:can_place_on — 可破坏/可放置方块

    minecraft:golden_pickaxe[minecraft:can_break={predicates:[{blocks:"minecraft:stone"}]}]

### minecraft:repair_cost — 铁砧惩罚

    minecraft:diamond_sword[minecraft:repair_cost=3]

### minecraft:block_state — 方块放置状态

    minecraft:bamboo_slab[minecraft:block_state={type:"top"}]

### minecraft:banner_patterns — 旗帜图案

    minecraft:black_banner[minecraft:banner_patterns=[{pattern:"triangle_top",color:"red"},{pattern:"cross",color:"white"}]]

### minecraft:base_color — 盾牌底色

    minecraft:shield[minecraft:base_color="lime"]

### minecraft:block_entity_data — 方块实体数据

    minecraft:spawner[minecraft:block_entity_data={id:"mob_spawner",SpawnData:{entity:{id:"spider"}}}]

### minecraft:container — 容器内容

    minecraft:chest[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:1}}]]

### minecraft:food — 食物属性

    minecraft:golden_apple[minecraft:food={nutrition:4,saturation:0.3,can_always_eat:false,eat_seconds:1.6}]

### minecraft:tool — 工具属性

    minecraft:diamond_pickaxe[minecraft:tool={damage_per_block:1,can_destroy_blocks_in_creative:true,mining_speed:8}]

### minecraft:enchantable — 可附魔等级

    minecraft:wooden_sword[minecraft:enchantable={value:15}]

### minecraft:repairable — 可修复材料

    minecraft:diamond_sword[minecraft:repairable={repair_items:[{items:"minecraft:diamond",count:3}]}]

### minecraft:consumable — 消耗品属性

    minecraft:golden_apple[minecraft:consumable={consume_seconds:1.6,animation:"eat",sound:"entity.generic.eat",has_consume_particles:true}]

### minecraft:use_remainder — 使用后替换

    minecraft:milk_bucket[minecraft:use_remainder={count:1,item:"minecraft:bucket"}]

### minecraft:use_cooldown — 使用冷却

    minecraft:ender_pearl[minecraft:use_cooldown={cooldown_seconds:1.0}]

### minecraft:equippable — 可装备属性

    minecraft:leather_helmet[minecraft:equippable={slot:"head",equip_sound:"item.armor.equip_leather"}]

### minecraft:max_stack_size — 最大堆叠数

    minecraft:ender_pearl[minecraft:max_stack_size=16]

### minecraft:max_damage — 最大耐久

    minecraft:stone_sword[minecraft:max_damage=131]

### minecraft:glider — 滑翔翅属性

    minecraft:elytra[minecraft:glider={}]

### minecraft:trim — 装备纹饰

    minecraft:diamond_chestplate[minecraft:trim={pattern:"minecraft:coast",material:"minecraft:iron"}]

### minecraft:death_protection — 死亡保护

    minecraft:totem_of_undying[minecraft:death_protection={death_effects:[{id:"minecraft:regeneration",amplifier:1,duration:400}]}]

### minecraft:tooltip_display — 工具提示控制

    minecraft:diamond_sword[minecraft:tooltip_display={hide_additional_tooltip:true}]

### minecraft:custom_data — 自定义数据（任意 SNBT）

    minecraft:stone[minecraft:custom_data={my_mod:{level:5}}]

### minecraft:bees — 蜂巢中的蜜蜂

    minecraft:bee_nest[minecraft:bees=[{entity_data:{id:"bee",CustomName:"Maya"},min_ticks_in_hive:60,ticks_in_hive:0}]]

## 旧标签 → 新组件 对照表

| 旧 NBT 标签 | 新组件 | 变化 |
| --- | --- | --- |
| display.Name | minecraft:custom_name | 包成文本组件对象 |
| display.Lore | minecraft:lore | 文本组件对象列表 |
| Enchantments | minecraft:enchantments | {id,lvl} 列表 → {"id":lvl} 映射（key 加引号） |
| StoredEnchantments | minecraft:stored_enchantments | 同上 |
| AttributeModifiers | minecraft:attribute_modifiers | operation 数字改名称；AttributeName 改为 type；值为列表；1.21.2+ 去掉 generic. 前缀 |
| Unbreakable | minecraft:unbreakable={} | 布尔标记 → 空组件 |
| SkullOwner | minecraft:profile | 名称与皮肤属性 |
| CanDestroy | minecraft:can_break | 方块 id → 方块谓词 |
| CanPlaceOn | minecraft:can_place_on | 方块 id → 方块谓词 |
| CustomModelData | minecraft:custom_model_data | 整数 |
| RepairCost | minecraft:repair_cost | 整数 |
| Potion / CustomPotionEffects | minecraft:potion_contents | 合并为一个组件 |
| EntityTag | minecraft:entity_data | 实体 NBT |
| Damage | minecraft:damage | 整数 |
| Display.color | minecraft:dyed_color | RGB 整数 |

## 常见报错

- `Malformed 'minecraft:attribute_modifiers' component: Not a list` → attribute_modifiers 的值必须是列表 [...]，不能套 {...}
- `Unknown registry key in ResourceKey[minecraft:root / minecraft:attribute]` → 属性 type ID 错误。1.21.2+ 不再用 minecraft:generic.xxx，直接写 minecraft:attack_damage（去掉 generic.）
- `Expected literal (` 或 `Unknown command` → enchantments 的 key 没加引号，必须写成 {"minecraft:sharpness":5} 不是 {minecraft:sharpness:5}
- `Unhandled exception executing` → 检查是否混用了旧 NBT 语法（{display:...}），或组件值格式错误
- 文本组件加了外层引号（如 custom_name='{"text":"..."}'）→ 会原样显示 JSON，去掉外层引号
