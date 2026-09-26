"use strict";

/* ───────────────────────── 状态 ───────────────────────── */

const state = {
    token: localStorage.getItem("huhobot_token") || "",
    schema: [],
    values: {},
    activeSection: null,
    dirty: false,
    groupNames: {},
    groupNamesSettled: false,
};

/* ───────────────────────── 工具 ───────────────────────── */

const $ = (sel) => document.querySelector(sel);

function showToast(msg, type = "info", ms = 2600) {
    const toast = $("#toast");
    toast.textContent = msg;
    toast.className = "toast" + (type === "success" ? " success" : type === "error" ? " error" : "");
    toast.classList.remove("hidden");
    // 强制回流后加上 show，保证连续弹出时动画也会重放
    void toast.offsetWidth;
    toast.classList.add("show");
    clearTimeout(showToast._t);
    showToast._t = setTimeout(() => {
        toast.classList.remove("show");
        setTimeout(() => toast.classList.add("hidden"), 240);
    }, ms);
}

/** 点击涟漪反馈 */
function attachRipple(el) {
    if (!el || el.dataset.ripple === "1") return;
    el.dataset.ripple = "1";
    el.addEventListener("click", (e) => {
        const rect = el.getBoundingClientRect();
        const size = Math.max(rect.width, rect.height);
        const ripple = document.createElement("span");
        ripple.className = "ripple";
        ripple.style.width = size + "px";
        ripple.style.height = size + "px";
        ripple.style.left = (e.clientX - rect.left - size / 2) + "px";
        ripple.style.top = (e.clientY - rect.top - size / 2) + "px";
        el.appendChild(ripple);
        setTimeout(() => ripple.remove(), 520);
    });
}

/** 从扁平(dotted)或嵌套 Map 中解析路径值。 */
function getPath(obj, path) {
    if (obj == null) return undefined;
    if (Object.prototype.hasOwnProperty.call(obj, path)) return obj[path];
    let cur = obj;
    for (const part of path.split(".")) {
        if (cur == null || typeof cur !== "object") return undefined;
        cur = cur[part];
    }
    return cur;
}

/** 是否为扁平 dotted 键映射（键本身含点）。 */
function isFlatValues(values) {
    return Object.keys(values).some((k) => k.includes("."));
}

/** 根据字段路径收集值。 */
function collectValue(values, field) {
    if (field.type === "command-map") {
        const map = {};
        const prefix = field.path + ".";
        for (const [key, value] of Object.entries(values)) {
            if (!key.startsWith(prefix)) continue;
            const remainder = key.slice(prefix.length);
            const [name, setting] = remainder.split(".");
            if (!name) continue;
            const entry = map[name] ||= { enable: true, pushMenu: true, priority: name === "agent" ? 0 : 100 };
            if (!setting && typeof value === "boolean") entry.enable = value;
            if (setting === "enable") entry.enable = !!value;
            if (setting === "pushMenu") entry.pushMenu = !!value;
            if (setting === "priority") entry.priority = Number(value);
        }
        return map;
    }
    if (field.type === "boolean-map") {
        const map = {};
        const prefix = field.path + ".";
        if (isFlatValues(values)) {
            for (const [k, v] of Object.entries(values)) {
                if (k.startsWith(prefix)) map[k.slice(prefix.length)] = !!v;
            }
        } else {
            const sub = getPath(values, field.path);
            if (sub && typeof sub === "object") {
                for (const [k, v] of Object.entries(sub)) map[k] = !!v;
            }
        }
        return map;
    }
    return getPath(values, field.path);
}

function esc(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

/* ───────────────────────── API ───────────────────────── */

async function api(path, options = {}) {
    const headers = { "Content-Type": "application/json" };
    if (state.token) headers["Authorization"] = "Bearer " + state.token;
    const resp = await fetch(path, { ...options, headers });
    let data = {};
    try { data = await resp.json(); } catch (_) { /* empty */ }
    if (resp.status === 401) {
        logout();
        throw new Error("未授权");
    }
    if (!resp.ok) {
        throw new Error(data.error || ("请求失败 " + resp.status));
    }
    return data;
}

async function login(password) {
    const data = await api("/api/login", {
        method: "POST",
        body: JSON.stringify({ password }),
    });
    state.token = data.token;
    localStorage.setItem("huhobot_token", data.token);
}

function logout() {
    state.token = "";
    localStorage.removeItem("huhobot_token");
    showLogin();
}

/* ───────────────────────── 视图切换 ───────────────────────── */

function showLogin() {
    $("#main-view").classList.add("hidden");
    $("#login-view").classList.remove("hidden");
    $("#login-password").value = "";
    $("#login-password").focus();
}

function showMain() {
    $("#login-view").classList.add("hidden");
    $("#main-view").classList.remove("hidden");
    renderNav();
    loadConfig().catch((e) => showToast(e.message, "error"));
}

/* ───────────────────────── 导航 ───────────────────────── */

const SECTION_ICONS = {
    bot: "🤖",
    server: "🖥️",
    "chat-format": "💬",
    "player-events": "📣",
    markdown: "📝",
    motd: "📋",
    whitelist: "✅",
    "filter-regex": "🔍",
    admin: "👑",
    features: "⚙️",
    audit: "🛡️",
    agent: "🧠",
    commands: "🔘",
    "custom-commands": "🧩",
    binding: "🔗",
    "command-blacklist": "🚫",
    "update-check": "🆕",
    "placeholder-api": "🔤",
    addons: "🧩",
};

function renderNav() {
    const nav = $("#sidebar-nav");
    nav.innerHTML = "";
    for (const section of state.schema) {
        const btn = document.createElement("button");
        btn.className = "nav-item";
        btn.dataset.section = section.key;
        btn.innerHTML =
            `<span class="nav-icon">${SECTION_ICONS[section.key] || "📄"}</span>` +
            `<span class="nav-label">${esc(section.title)}</span>`;
        btn.addEventListener("click", () => {
            if (state.dirty && !confirm("有未保存的修改，切换到其他页面将丢失。继续？")) return;
            state.activeSection = section.key;
            state.dirty = false;
            renderSection(section);
            updateNavActive();
        });
        attachRipple(btn);
        nav.appendChild(btn);
    }

    const addonBtn = document.createElement("button");
    addonBtn.className = "nav-item";
    addonBtn.dataset.section = "addons";
    addonBtn.innerHTML =
        `<span class="nav-icon">${SECTION_ICONS.addons}</span>` +
        `<span class="nav-label">附属插件中心</span>`;
    addonBtn.addEventListener("click", () => {
        if (state.dirty && !confirm("有未保存的修改，切换到其他页面将丢失。继续？")) return;
        state.activeSection = "addons";
        state.dirty = false;
        renderSection({ key: "addons", title: "附属插件中心", fields: [] });
        updateNavActive();
    });
    attachRipple(addonBtn);
    nav.appendChild(addonBtn);
}

function updateNavActive() {
    document.querySelectorAll(".nav-item").forEach((el) => {
        el.classList.toggle("active", el.dataset.section === state.activeSection);
    });
}

/* ───────────────────────── 配置加载与渲染 ───────────────────────── */

async function loadConfig() {
    const data = await api("/api/config");
    state.schema = data.schema || [];
    state.values = data.values || {};
    state.groupNames = data.groupNames || {};
    state.groupNamesSettled = false;
    groupNameAttempts = 0;
    state.platform = data.platform || "";
    renderNav();
    if (!state.activeSection) state.activeSection = state.schema[0]?.key || null;
    const section = state.schema.find((s) => s.key === state.activeSection);
    if (section) renderSection(section);
    updateNavActive();
    refreshGroupNames();
}

/* 群名称在后台异步补齐，取到后重绘当前分节 */
let groupNameAttempts = 0;

async function refreshGroupNames() {
    groupNameAttempts += 1;
    let groups = [];
    try {
        const data = await api("/api/status");
        groups = Array.isArray(data.groups) ? data.groups : [];
        let changed = false;
        for (const g of groups) {
            if (typeof g === "string") continue;
            if (g.name && state.groupNames[g.openId] !== g.name) {
                state.groupNames[g.openId] = g.name;
                changed = true;
            }
        }
        if (changed) {
            const section = state.schema.find((s) => s.key === state.activeSection);
            if (section) renderSection(section);
        }
        const pending = groups.some((g) => typeof g !== "string" && !g.name);
        if (!pending) {
            state.groupNamesSettled = true;
            return;
        }
        if (groupNameAttempts < 8) {
            setTimeout(refreshGroupNames, 1500);
        } else {
            state.groupNamesSettled = true;
            const section = state.schema.find((s) => s.key === state.activeSection);
            if (section) renderSection(section);
        }
    } catch (_) {
        if (groupNameAttempts < 8) {
            setTimeout(refreshGroupNames, 2000);
        } else {
            state.groupNamesSettled = true;
        }
    }
}

function renderSection(section) {
    $("#config-panel").classList.remove("hidden");
    $("#status-panel").classList.add("hidden");
    if (section.key === "addons") {
        renderAddonPanel(section);
        return;
    }
    $("#save-btn").classList.remove("hidden");
    $("#config-section-title").textContent = section.title;
    $("#config-section-desc").textContent = section.fields
        .map((f) => f.description)
        .filter(Boolean)
        .join("；");

    const form = $("#config-form");
    form.innerHTML = "";
    form.dataset.section = section.key;

    for (const field of section.fields) {
        form.appendChild(renderField(field));
    }
}

/* ───────────────────────── 附属插件中心 ───────────────────────── */

function formatBytes(bytes) {
    const n = Number(bytes) || 0;
    if (n <= 0) return "-";
    if (n >= 1048576) return (n / 1048576).toFixed(1) + " MB";
    if (n >= 1024) return Math.round(n / 1024) + " KB";
    return n + " B";
}

function renderAddonPanel(section) {
    const form = $("#config-form");
    form.innerHTML = "";
    $("#save-btn").classList.add("hidden");
    $("#config-section-title").textContent = section.title;
    $("#config-section-desc").textContent =
        "从附属插件中心获取 Spigot / Paper 平台的扩展；下载后需重启服务器才会生效（不做热加载）";

    const toolbar = document.createElement("div");
    toolbar.className = "addon-toolbar";
    const search = document.createElement("input");
    search.className = "input";
    search.placeholder = "搜索插件名称或简介";
    const refresh = document.createElement("button");
    refresh.className = "btn btn-ghost btn-sm";
    refresh.textContent = "刷新";
    toolbar.appendChild(search);
    toolbar.appendChild(refresh);

    const list = document.createElement("div");
    list.className = "addon-list";
    list.innerHTML = '<div class="addon-empty">正在加载附属插件…</div>';

    form.appendChild(toolbar);
    form.appendChild(list);

    const load = async (keyword) => {
        list.innerHTML = '<div class="addon-empty">正在加载附属插件…</div>';
        const query = keyword ? `?search=${encodeURIComponent(keyword)}` : "";
        const data = await api("/api/addons" + query);
        const plugins = Array.isArray(data.plugins) ? data.plugins : [];
        const loadedNames = new Set(
            (Array.isArray(data.installed) ? data.installed : []).map((s) => String(s).toLowerCase())
        );
        const records = Array.isArray(data.installedRecords) ? data.installedRecords : [];
        const recordById = new Map(records.map((r) => [r.id, r]));
        const recordByName = new Map(records.map((r) => [String(r.name).toLowerCase(), r]));
        list.innerHTML = "";
        if (!plugins.length) {
            list.innerHTML = '<div class="addon-empty">没有找到符合条件的插件</div>';
            return;
        }
        for (const p of plugins) {
            const record = recordById.get(p.id) || recordByName.get(String(p.name).toLowerCase());
            list.appendChild(buildAddonCard(p, loadedNames, record || null));
        }
    };

    const run = () => load(search.value.trim()).catch((e) => {
        list.innerHTML = `<div class="addon-empty">加载失败：${esc(e.message)}</div>`;
    });
    search.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            e.preventDefault();
            run();
        }
    });
    attachRipple(refresh);
    refresh.addEventListener("click", run);
    run();
}

function buildAddonCard(addon, installed, record) {
    const card = document.createElement("div");
    card.className = "addon-card";

    const isInstalled = installed.has(String(addon.name).toLowerCase()) || !!record;
    const tags = (Array.isArray(addon.tags) ? addon.tags : [])
        .map((t) => `<span class="addon-tag">${esc(t)}</span>`).join("");

    card.innerHTML =
        `<div class="addon-head">` +
        `<div class="addon-title">${esc(addon.name)}${isInstalled ? '<span class="badge-ok">已安装</span>' : ""}</div>` +
        `<div class="addon-version">v${esc(addon.version || "-")}</div>` +
        `</div>` +
        `<div class="addon-desc">${esc(addon.description || "暂无简介")}</div>` +
        `<div class="addon-meta">` +
        `<span>作者：${esc(addon.author || "-")}</span>` +
        `<span>平台：${esc(addon.serverType || "-")}</span>` +
        `<span>大小：${esc(formatBytes(addon.fileSize))}</span>` +
        `<span>下载：${esc(String(addon.downloads ?? 0))}</span>` +
        (record && record.file ? `<span>文件：${esc(record.file)}</span>` : "") +
        `</div>` +
        (tags ? `<div class="addon-tags">${tags}</div>` : "") +
        `<div class="addon-actions">` +
        `<button class="btn btn-ghost btn-sm" data-act="detail">详情</button>` +
        `<button class="btn btn-primary btn-sm" data-act="install"${isInstalled ? " disabled" : ""}>` +
        `${isInstalled ? "已安装" : "下载安装"}</button>` +
        (record && record.file
            ? `<button class="btn btn-danger-ghost btn-sm" data-act="remove">删除</button>`
            : "") +
        `</div>` +
        `<pre class="addon-readme hidden"></pre>`;

    const readme = card.querySelector(".addon-readme");
    const detailBtn = card.querySelector('[data-act="detail"]');
    attachRipple(detailBtn);
    detailBtn.addEventListener("click", () => {
        const hidden = readme.classList.toggle("hidden");
        if (!hidden && !readme.textContent) {
            readme.textContent = addon.readme || "该插件没有提供说明文档。";
        }
        detailBtn.textContent = hidden ? "详情" : "收起";
    });

    const installBtn = card.querySelector('[data-act="install"]');
    attachRipple(installBtn);
    installBtn.addEventListener("click", async () => {
        installBtn.classList.add("is-busy");
        try {
            const data = await api("/api/addons/install", {
                method: "POST",
                body: JSON.stringify({ id: addon.id }),
            });
            showToast(`已下载 ${data.file}，重启服务器后生效`, "success", 4200);
            card.querySelector(".addon-title").insertAdjacentHTML("beforeend", '<span class="badge-ok">已安装</span>');
            installBtn.disabled = true;
            installBtn.textContent = "已安装";
        } catch (e) {
            showToast("下载失败：" + e.message, "error");
        } finally {
            installBtn.classList.remove("is-busy");
        }
    });

    const removeBtn = card.querySelector('[data-act="remove"]');
    if (removeBtn) {
        attachRipple(removeBtn);
        removeBtn.addEventListener("click", async () => {
            if (!confirm(`确定删除 ${record.file}？删除后重启服务器生效。`)) return;
            removeBtn.classList.add("is-busy");
            try {
                await api("/api/addons/remove", {
                    method: "POST",
                    body: JSON.stringify({ file: record.file }),
                });
                showToast(`已删除 ${record.file}，重启服务器后生效`, "success", 4200);
                const badge = card.querySelector(".badge-ok");
                if (badge) badge.remove();
                removeBtn.remove();
                installBtn.disabled = false;
                installBtn.textContent = "下载安装";
            } catch (e) {
                showToast("删除失败：" + e.message, "error");
            } finally {
                removeBtn.classList.remove("is-busy");
            }
        });
    }

    return card;
}

function renderField(field) {
    const wrap = document.createElement("div");
    wrap.className = "field-card" + (field.type === "boolean" ? " field-boolean" : "");
    wrap.dataset.path = field.path;

    const labelBlock = document.createElement("div");
    labelBlock.className = "field-text";
    labelBlock.innerHTML =
        `<div class="field-label">${esc(field.label)}</div>` +
        (field.description ? `<div class="field-desc">${esc(field.description)}</div>` : "");

    const control = buildControl(field);
    if (field.type === "boolean") {
        wrap.appendChild(labelBlock);
        wrap.appendChild(control);
    } else {
        wrap.appendChild(labelBlock);
        wrap.appendChild(control);
    }
    return wrap;
}

function buildControl(field) {
    const value = collectValue(state.values, field);

    switch (field.type) {
        case "text":
        case "password":
        case "number":
        case "textarea": {
            const el = document.createElement(field.type === "textarea" ? "textarea" : "input");
            el.className = "input";
            el.dataset.path = field.path;
            el.dataset.type = field.type;
            if (field.type === "password") el.type = "password";
            if (field.type === "number") el.type = "number";
            if (field.placeholder) el.placeholder = field.placeholder;
            if (value != null) el.value = value;
            el.addEventListener("input", () => { state.dirty = true; });
            return el;
        }

        case "boolean": {
            const label = document.createElement("label");
            label.className = "switch";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.dataset.path = field.path;
            input.dataset.type = "boolean";
            input.checked = !!value;
            input.addEventListener("change", () => { state.dirty = true; });
            const track = document.createElement("span");
            track.className = "track";
            label.appendChild(input);
            label.appendChild(track);
            return label;
        }

        case "select": {
            const el = document.createElement("select");
            el.className = "input";
            el.dataset.path = field.path;
            el.dataset.type = "select";
            for (const opt of field.options) {
                const o = document.createElement("option");
                o.value = opt;
                o.textContent = opt;
                if (String(value) === String(opt)) o.selected = true;
                el.appendChild(o);
            }
            el.addEventListener("change", () => { state.dirty = true; });
            return el;
        }

        case "list":
            return buildListControl(field, value);

        case "boolean-map":
            return buildBooleanMapControl(field, value);

        case "command-map":
            return buildCommandMapControl(field, value);

        case "object-list":
            return buildObjectListControl(field, value);

        default:
            const el = document.createElement("input");
            el.className = "input";
            el.dataset.path = field.path;
            el.value = value == null ? "" : value;
            return el;
    }
}

/* 标签式列表 */
function buildListControl(field, value) {
    const items = Array.isArray(value) ? value.map(String) : [];
    const isGroupList = field.path === "bot.groups";
    const container = document.createElement("div");
    container.className = "list-input";
    container.dataset.path = field.path;
    container.dataset.type = "list";

    function displayText(item) {
        if (!isGroupList) return esc(item);
        const name = state.groupNames[item];
        if (name) return `${esc(name)}<span class="tag-suffix">…${esc(item.slice(-6))}</span>`;
        const suffix = `<span class="tag-suffix">…${esc(item.slice(-6))}</span>`;
        return state.groupNamesSettled
            ? `名称暂不可用${suffix}`
            : `正在获取…${suffix}`;
    }

    function renderTags() {
        container.querySelectorAll(".tag").forEach((t) => t.remove());
        items.forEach((item, idx) => {
            const tag = document.createElement("span");
            tag.className = "tag";
            tag.title = isGroupList ? `点击复制 ${item}` : item;
            tag.innerHTML =
                `<span class="tag-label" data-value="${esc(item)}">${displayText(item)}</span>` +
                `<span class="tag-remove" data-idx="${idx}">×</span>`;
            if (isGroupList) {
                tag.querySelector(".tag-label").addEventListener("click", () => {
                    navigator.clipboard?.writeText(item);
                    showToast("已复制群 OpenID");
                });
            }
            tag.querySelector(".tag-remove").addEventListener("click", () => {
                items.splice(idx, 1);
                state.dirty = true;
                renderTags();
            });
            container.insertBefore(tag, container.querySelector(".list-add-row"));
        });
    }

    const row = document.createElement("div");
    row.className = "list-add-row";
    const input = document.createElement("input");
    input.className = "input";
    input.placeholder = field.placeholder || "输入后回车添加";
    const addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "btn btn-ghost btn-sm";
    addBtn.textContent = "添加";
    attachRipple(addBtn);
    addBtn.addEventListener("click", () => {
        const v = input.value.trim();
        if (v && !items.includes(v)) {
            items.push(v);
            state.dirty = true;
            renderTags();
        }
        input.value = "";
        input.focus();
    });
    input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") { e.preventDefault(); addBtn.click(); }
    });
    row.appendChild(input);
    row.appendChild(addBtn);
    container.appendChild(row);
    renderTags();
    return container;
}

/* 布尔映射（命令开关） */
function buildBooleanMapControl(field, value) {
    const container = document.createElement("div");
    container.className = "boolean-map";
    container.dataset.path = field.path;
    container.dataset.type = "boolean-map";

    const map = value || {};
    const keys = Object.keys(map);

    if (keys.length === 0) {
        const hint = document.createElement("div");
        hint.className = "empty-hint";
        hint.textContent = "暂无开关项，保存后自动生成。";
        container.appendChild(hint);
        return container;
    }

    for (const name of keys) {
        const item = document.createElement("div");
        item.className = "boolean-map-item";
        const text = document.createElement("div");
        text.className = "bm-name";
        text.textContent = name;
        const label = document.createElement("label");
        label.className = "switch";
        const input = document.createElement("input");
        input.type = "checkbox";
        input.dataset.name = name;
        input.checked = !!map[name];
        input.addEventListener("change", () => { state.dirty = true; });
        const track = document.createElement("span");
        track.className = "track";
        label.appendChild(input);
        label.appendChild(track);
        item.appendChild(text);
        item.appendChild(label);
        container.appendChild(item);
    }
    return container;
}

function buildCommandMapControl(field, value) {
    const container = document.createElement("div");
    container.className = "command-map";
    container.dataset.path = field.path;
    container.dataset.type = "command-map";
    const entries = Object.entries(value || {}).sort(([a], [b]) => a.localeCompare(b, "zh-CN"));
    const header = document.createElement("div");
    header.className = "command-map-row command-map-header";
    header.innerHTML = "<span>命令</span><span>启用</span><span>面板</span><span>优先级</span>";
    container.appendChild(header);
    for (const [name, settings] of entries) {
        const row = document.createElement("div");
        row.className = "command-map-row";
        row.dataset.name = name;
        const title = document.createElement("span");
        title.textContent = name;
        row.appendChild(title);
        for (const key of ["enable", "pushMenu"]) {
            const label = document.createElement("label");
            label.className = "switch";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.dataset.setting = key;
            checkbox.checked = !!settings[key];
            checkbox.setAttribute("aria-label", `${name} ${key === "enable" ? "启用" : "显示在面板"}`);
            checkbox.addEventListener("change", () => { state.dirty = true; });
            const track = document.createElement("span");
            track.className = "track";
            label.appendChild(checkbox);
            label.appendChild(track);
            row.appendChild(label);
        }
        const priority = document.createElement("input");
        priority.className = "input";
        priority.type = "number";
        priority.min = "0";
        priority.max = "999";
        priority.step = "1";
        priority.dataset.setting = "priority";
        priority.value = Number.isFinite(settings.priority) ? settings.priority : 100;
        priority.setAttribute("aria-label", `${name} 优先级`);
        priority.title = "点击后可用鼠标滚轮调整";
        priority.addEventListener("input", () => { state.dirty = true; });
        priority.addEventListener("wheel", (event) => {
            if (document.activeElement !== priority || event.ctrlKey || event.deltaY === 0) return;
            event.preventDefault();
            const current = Number.isFinite(priority.valueAsNumber) ? priority.valueAsNumber : 100;
            const next = Math.min(999, Math.max(0, Math.trunc(current) + (event.deltaY < 0 ? 1 : -1)));
            if (next !== current) {
                priority.value = String(next);
                priority.dispatchEvent(new Event("input", { bubbles: true }));
            }
        }, { passive: false });
        row.appendChild(priority);
        container.appendChild(row);
    }
    return container;
}

/* 对象列表（自定义命令） */
function buildObjectListControl(field, value) {
    const container = document.createElement("div");
    container.className = "object-list";
    container.dataset.path = field.path;
    container.dataset.type = "object-list";

    const rows = Array.isArray(value) ? value.map((r) => ({ ...r })) : [];

    function renderRows() {
        container.querySelectorAll(".object-row").forEach((el) => el.remove());
        const addWrap = container.querySelector(".add-row-wrap");
        rows.forEach((row, idx) => {
            const div = document.createElement("div");
            div.className = "object-row";
            div.dataset.idx = idx;

            const fieldsDiv = document.createElement("div");
            fieldsDiv.className = "row-fields";
            for (const sub of field.fields) {
                const col = document.createElement("div");
                const label = document.createElement("div");
                label.className = "row-label";
                label.textContent = sub.label;
                if (sub.type === "boolean") {
                    const switchLabel = document.createElement("label");
                    switchLabel.className = "switch";
                    const input = document.createElement("input");
                    input.type = "checkbox";
                    const raw = row[sub.path];
                    input.checked = raw == null ? true : raw === true || String(raw) === "true";
                    input.dataset.subpath = sub.path;
                    input.addEventListener("change", () => { state.dirty = true; });
                    const track = document.createElement("span");
                    track.className = "track";
                    switchLabel.appendChild(input);
                    switchLabel.appendChild(track);
                    col.appendChild(label);
                    col.appendChild(switchLabel);
                } else {
                    const input = document.createElement("input");
                    input.className = "input";
                    input.type = sub.type === "number" ? "number" : "text";
                    input.value = row[sub.path] ?? "";
                    input.dataset.subpath = sub.path;
                    input.addEventListener("input", () => { state.dirty = true; });
                    col.appendChild(label);
                    col.appendChild(input);
                }
                fieldsDiv.appendChild(col);
            }
            div.appendChild(fieldsDiv);

            const actions = document.createElement("div");
            actions.className = "row-actions";
            const delBtn = document.createElement("button");
            delBtn.type = "button";
            delBtn.className = "btn btn-danger-ghost btn-sm";
            delBtn.textContent = "删除";
            delBtn.addEventListener("click", () => {
                rows.splice(idx, 1);
                state.dirty = true;
                renderRows();
            });
            actions.appendChild(delBtn);
            div.appendChild(actions);
            container.insertBefore(div, addWrap);
        });
    }

    const addWrap = document.createElement("div");
    addWrap.className = "add-row-wrap";
    const addBtn = document.createElement("button");
    addBtn.type = "button";
    addBtn.className = "btn btn-ghost btn-sm";
    addBtn.textContent = "＋ 添加一行";
    addBtn.addEventListener("click", () => {
        rows.push({});
        state.dirty = true;
        renderRows();
    });
    addWrap.appendChild(addBtn);
    container.appendChild(addWrap);
    renderRows();
    return container;
}

/* ───────────────────────── 保存 ───────────────────────── */

function collectChanges() {
    const changes = {};
    const form = $("#config-form");
    const section = state.schema.find((s) => s.key === form.dataset.section);
    if (!section) return changes;

    for (const field of section.fields) {
        const nodes = form.querySelectorAll(`[data-path="${CSS.escape(field.path)}"]`);
        for (const node of nodes) {
            switch (node.dataset.type) {
                case "text":
                case "password":
                case "select":
                case "textarea":
                case undefined: {
                    if (node.type === "checkbox") continue;
                    const val = node.value;
                    changes[field.path] =
                        field.type === "number" ? Number(val || 0) : val;
                    break;
                }
                case "number": {
                    changes[field.path] = Number(node.value || 0);
                    break;
                }
                case "boolean": {
                    changes[field.path] = node.checked;
                    break;
                }
                case "list": {
                    const items = [];
                    node.querySelectorAll(".tag > span:first-child").forEach((s) => items.push(s.dataset.value ?? s.textContent));
                    changes[field.path] = items;
                    break;
                }
                case "boolean-map": {
                    node.querySelectorAll("input[type=checkbox]").forEach((cb) => {
                        changes[field.path + "." + cb.dataset.name] = cb.checked;
                    });
                    break;
                }
                case "command-map": {
                    node.querySelectorAll(".command-map-row[data-name]").forEach((row) => {
                        const name = row.dataset.name;
                        const prefix = field.path + "." + name + ".";
                        changes[prefix + "enable"] = row.querySelector('[data-setting="enable"]').checked;
                        changes[prefix + "pushMenu"] = row.querySelector('[data-setting="pushMenu"]').checked;
                        const priority = Number(row.querySelector('[data-setting="priority"]').value);
                        changes[prefix + "priority"] = Math.min(999, Math.max(0, Number.isFinite(priority) ? priority : 100));
                    });
                    break;
                }
                case "object-list": {
                    const rows = [];
                    node.querySelectorAll(".object-row").forEach((rowEl) => {
                        const obj = {};
                        rowEl.querySelectorAll("input[data-subpath]").forEach((inp) => {
                            obj[inp.dataset.subpath] =
                                inp.type === "checkbox"
                                    ? inp.checked
                                    : inp.type === "number"
                                        ? Number(inp.value || 0)
                                        : inp.value;
                        });
                        rows.push(obj);
                    });
                    changes[field.path] = rows;
                    break;
                }
            }
        }
    }
    return changes;
}

async function saveConfig() {
    const btn = $("#save-btn");
    const changes = collectChanges();
    btn.classList.add("is-busy");
    try {
        await api("/api/config", {
            method: "POST",
            body: JSON.stringify({ changes }),
        });
        // 保存后重新从配置文件读取最新值
        const data = await api("/api/config");
        state.values = data.values || {};
        // 重新渲染当前分节，使界面立即反映保存结果
        const section = state.schema.find((s) => s.key === state.activeSection);
        if (section) renderSection(section);
        state.dirty = false;
        showToast("配置已保存并重载 ✓", "success");
        loadStatus().catch(() => {});
    } catch (e) {
        showToast("保存失败：" + e.message, "error");
    } finally {
        btn.classList.remove("is-busy");
    }
}

/* ───────────────────────── 状态面板 ───────────────────────── */

async function loadStatus() {
    const data = await api("/api/status");
    const grid = $("#status-cards");
    grid.innerHTML = "";

    state.groupNames = {};
    const groupLines = (Array.isArray(data.groups) ? data.groups : []).map((g) => {
        if (typeof g === "string") {
            state.groupNames[g] = "";
            return g;
        }
        state.groupNames[g.openId] = g.name || "";
        return g.name ? `${g.name}（…${g.suffix}）` : `名称暂不可用（…${g.suffix}）`;
    });

    const cards = [
        ["平台", data.platform || "-"],
        ["版本", data.version || "-"],
        ["服务器名称", data.serverName || "-"],
        ["机器人名称", data.botName || "-"],
        ["AppID", data.appId || "-"],
        ["QQ 连接", data.qqConnected ? "✅ 已连接" : "⚠️ 未连接", data.qqConnected ? "ok" : "bad"],
        ["Agent", data.agentEnabled ? "✅ 已启用" : "—— 未启用", data.agentEnabled ? "ok" : ""],
        ["在线玩家", Array.isArray(data.online) ? data.online.join(", ") || "无" : "-", "dim"],
        ["绑定群数量", String(groupLines.length)],
        ["绑定群", groupLines.join("\n") || "未配置（不限制）", "dim"],
    ];

    for (const [label, value, cls] of cards) {
        const card = document.createElement("div");
        card.className = "status-card";
        card.innerHTML =
            `<div class="sc-label">${esc(label)}</div>` +
            `<div class="sc-value ${cls || ""}">${esc(value)}</div>`;
        grid.appendChild(card);
    }
}

/* ───────────────────────── 初始化 ───────────────────────── */

function init() {
    // 阻止配置表单的默认提交（防止任何 button/input 回车触发页面跳转）
    $("#config-form").addEventListener("submit", (e) => e.preventDefault());

    // 登录
    $("#login-btn").addEventListener("click", doLogin);
    $("#login-password").addEventListener("keydown", (e) => {
        if (e.key === "Enter") doLogin();
    });

    // 主界面
    $("#save-btn").addEventListener("click", saveConfig);
    $("#logout-btn").addEventListener("click", logout);
    $("#status-btn").addEventListener("click", () => {
        if (state.dirty && !confirm("有未保存的修改，切换到状态页将丢失。继续？")) return;
        state.dirty = false;
        $("#config-panel").classList.add("hidden");
        $("#status-panel").classList.remove("hidden");
        updateNavActive();
        loadStatus().catch((e) => showToast(e.message, "error"));
    });

    // 静态按钮点击反馈
    [$("#login-btn"), $("#save-btn"), $("#logout-btn"), $("#status-btn")].forEach(attachRipple);

    if (state.token) {
        showMain();
    } else {
        showLogin();
    }
}

async function doLogin() {
    const pwd = $("#login-password").value;
    if (!pwd) return;
    const errEl = $("#login-error");
    errEl.classList.add("hidden");
    try {
        await login(pwd);
        showMain();
    } catch (e) {
        errEl.textContent = e.message === "未授权" ? "密码错误" : e.message;
        errEl.classList.remove("hidden");
    }
}

document.addEventListener("DOMContentLoaded", init);
