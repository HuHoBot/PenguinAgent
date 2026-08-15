# /summon 命令 SKILL

## 语法

    /summon <实体> [<位置>] [<nbt>]

- `<实体>`：实体类型 ID（不能召唤 player 或 fishing_bobber）
- `<位置>`：x y z，默认为执行者位置
- `<nbt>`：SNBT 格式实体数据

## 关键变化（1.20.5+）

实体 NBT 仍是 SNBT 格式，但**物品字段**使用 item_stack 格式（带 components）：

- 旧：`{Item:{id:"minecraft:diamond_sword",Count:1b,tag:{Enchantments:[{id:"minecraft:sharpness",lvl:5}]}}}`
- 新：`{Item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":5}}}}`

## 常见实体 NBT 字段

- CustomName：自定义名称（JSON 文本组件）
- CustomNameVisible：是否始终显示名称（1b/0b）
- NoAI：禁用 AI（1b/0b）
- Silent：静音（1b/0b）
- Invulnerable：无敌（1b/0b）
- Glowing：发光（1b/0b）
- PersistenceRequired：不会自然消失（1b/0b）
- HandItems：双手物品（[{右手法器}, {左手物品}]，每个是 {id, count, components}）
- ArmorItems：护甲物品（[{脚}, {腿}, {胸}, {头}]，每个是 {id, count, components}）
- ArmorDropChances：护甲掉落概率
- HandDropChances：手持物品掉落概率
- ActiveEffects：药水效果列表
- Health：当前生命值
- AbsorptionAmount：吸收生命值

## 示例

### 基础召唤

- /summon zombie ~ ~ ~
- /summon creeper ~ ~ ~ {powered:true,CustomName:'"高压苦力怕"'}
- /summon zombie ~ ~ ~ {IsBaby:true,CustomName:'"小僵尸"',CustomNameVisible:1b}

### 带装备的实体

- /summon zombie ~ ~ ~ {HandItems:[{id:"minecraft:netherite_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":5}}},{}],ArmorItems:[{},{},{},{id:"minecraft:netherite_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4}}}],HandDropChances:[0.5f,0f],ArmorDropChances:[0f,0f,0f,1f]}

### 带药水效果

- /summon zombie ~ ~ ~ {active_effects:[{id:"minecraft:strength",amplifier:2b,duration:-1,show_particles:true},{id:"minecraft:speed",amplifier:1b,duration:-1,show_particles:true}],HandItems:[{id:"minecraft:netherite_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":10}}},{}]}

### 掉落物（物品实体）

- /summon item ~ ~ ~ {Item:{id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":{text:"神剑",color:"gold"},"minecraft:enchantments":{"minecraft:sharpness":10}}},Age:5900}

### 盔甲架

- /summon armor_stand ~ ~ ~ {ShowArms:true,HandItems:[{id:"minecraft:netherite_sword",count:1,components:{"minecraft:enchantments":{"minecraft:sharpness":5}}},{}],ArmorItems:[{},{},{},{id:"minecraft:netherite_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4}}}]}

### 骷髅带装备

- /summon skeleton ~ ~ ~ {HandItems:[{id:"minecraft:bow",count:1,components:{"minecraft:enchantments":{"minecraft:power":5,"minecraft:flame":1,"minecraft:infinity":1}}},{}],ArmorItems:[{id:"minecraft:netherite_boots",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4}}},{id:"minecraft:netherite_leggings",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4}}},{id:"minecraft:netherite_chestplate",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4,"minecraft:thorns":3}}},{id:"minecraft:netherite_helmet",count:1,components:{"minecraft:enchantments":{"minecraft:protection":4}}}]}

### 蜜蜂

- /summon bee ~ ~ ~ {CustomName:'"小蜜"',HasStung:false,AngerTime:0}

## 注意

- 实体 NBT 中的物品字段格式：`{id:"物品id",count:数量,components:{组件名:值}}`
- id 字段必须带 minecraft: 前缀（如 "minecraft:diamond_sword"）
- count 是整数（不带 b 后缀），不是字节
- 组件值格式与 /give 命令相同（详见 skill-components.md）
