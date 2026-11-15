"use strict";

/* ============================================================
   PenguinAgent WebUI
   原生 JS · 无第三方依赖 · 仅当前分节渲染
   ============================================================ */

const TOKEN_KEY = "huhobot_token";

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
const SVG_NS = "http://www.w3.org/2000/svg";

const state = {
    token: localStorage.getItem(TOKEN_KEY) || "",
    schema: [],
    sectionMap: new Map(),
    values: {},
    groups: [],
    groupNames: {},
    groupNamesSettled: false,
    groupNameTimer: null,
    groupNamePolling: false,
    platform: "",
    active: null,
    dirty: false,
    scope: "global",
    loadedScope: "global",
    status: null,
    statusLoading: false,
    addonsData: null,
    addonsLoading: false,
    addonPending: false,
    addonQuery: "",
    addonSearchTimer: null,
    qrTimer: null,
    qrSuccessHandled: false,
    navSignature: "",
    navSeq: 0,
};

/* ───────────────────────── 通用工具 ───────────────────────── */

function h(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
}

function icon(id, className = "icon") {
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("class", className);
    svg.setAttribute("aria-hidden", "true");
    const use = document.createElementNS(SVG_NS, "use");
    use.setAttribute("href", "#" + id);
    svg.appendChild(use);
    return svg;
}

function setIcon(svg, id) {
    const use = svg && svg.querySelector("use");
    if (use) use.setAttribute("href", "#" + id);
}

function button(className, label, iconId, attrs = {}) {
    const btn = h("button", className);
    btn.type = "button";
    if (iconId) btn.appendChild(icon(iconId));
    if (label) btn.appendChild(h("span", null, label));
    for (const [key, value] of Object.entries(attrs)) {
        if (key === "dataset") {
            for (const [dk, dv] of Object.entries(value)) btn.dataset[dk] = dv;
        } else {
            btn.setAttribute(key, value);
        }
    }
    return btn;
}

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

function isFlatValues(values) {
    return Object.keys(values || {}).some((key) => key.includes("."));
}

function collectValue(values, field) {
    if (field.type === "command-map") {
        const map = {};
        const prefix = field.path + ".";
        for (const [key, value] of Object.entries(values || {})) {
            if (!key.startsWith(prefix)) continue;
            const remainder = key.slice(prefix.length);
            const [name, setting] = remainder.split(".");
            if (!name) continue;
            const entry = map[name] || (map[name] = {
                enable: true,
                pushMenu: true,
                priority: name === "agent" ? 0 : 100,
            });
            if (!setting && typeof value === "boolean") entry.enable = value;
            if (setting === "enable") entry.enable = !!value;
            if (setting === "pushMenu") entry.pushMenu = !!value;
            if (setting === "priority" && Number.isFinite(Number(value))) entry.priority = Number(value);
        }
        return map;
    }
    if (field.type === "boolean-map") {
        const map = {};
        const prefix = field.path + ".";
        if (isFlatValues(values)) {
            for (const [key, value] of Object.entries(values || {})) {
                if (key.startsWith(prefix)) map[key.slice(prefix.length)] = !!value;
            }
        } else {
            const sub = getPath(values, field.path);
            if (sub && typeof sub === "object") {
                for (const [key, value] of Object.entries(sub)) map[key] = !!value;
            }
        }
        return map;
    }
    return getPath(values, field.path);
}

function safeNumber(value, fallback = 0) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
}

async function copyText(text) {
    const value = String(text == null ? "" : text);
    try {
        await navigator.clipboard.writeText(value);
    } catch (_) {
        const area = h("textarea");
        area.value = value;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.appendChild(area);
        area.select();
        try { document.execCommand("copy"); } catch (_) { /* ignore */ }
        area.remove();
    }
    toast("已复制", "success", 1600);
}

function formatBytes(bytes) {
    const n = Number(bytes) || 0;
    if (n <= 0) return "-";
    if (n >= 1048576) return (n / 1048576).toFixed(1) + " MB";
    if (n >= 1024) return Math.round(n / 1024) + " KB";
    return n + " B";
}

/* ───────────────────────── Toast ───────────────────────── */

let toastTimer = null;

function toast(message, type = "info", ms = 2800) {
    const el = $("#toast");
    if (!el) return;
    $("#toast-text").textContent = message;
    el.className = "toast show " + (type === "success" ? "success" : type === "error" ? "error" : "");
    setIcon($("#toast .icon"), type === "success" ? "i-check" : type === "error" ? "i-alert" : "i-info");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        el.classList.remove("show");
    }, ms);
}

/* ───────────────────────── API / 鉴权 ───────────────────────── */

async function request(path, { method = "GET", body, redirect = true } = {}) {
    const headers = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (state.token) headers["Authorization"] = "Bearer " + state.token;

    let response;
    try {
        response = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (_) {
        throw new Error("无法连接到服务器");
    }

    const text = await response.text();
    let data = {};
    if (text) {
        try { data = JSON.parse(text); } catch (_) { data = {}; }
    }

    if (response.status === 401) {
        if (redirect && state.token) {
            logout();
            toast("登录已失效，请重新登录", "error");
        }
        throw new Error(data.error || "未授权");
    }
    if (!response.ok) {
        throw new Error(data.error || ("请求失败（" + response.status + "）"));
    }
    return data;
}

/* ───────────────────────── 导航元信息 ───────────────────────── */

const SECTION_ICONS = {
    bot: "i-bot",
    server: "i-server",
    "chat-format": "i-chat",
    "player-events": "i-bell",
    "server-notify": "i-bell",
    markdown: "i-file",
    motd: "i-globe",
    whitelist: "i-list",
    "filter-regex": "i-filter",
    admin: "i-crown",
    features: "i-sliders",
    audit: "i-shield",
    agent: "i-spark",
    commands: "i-terminal",
    "custom-commands": "i-code",
    binding: "i-link",
    "command-blacklist": "i-ban",
    "update-check": "i-refresh",
    "placeholder-api": "i-code",
};

const VIRTUAL_META = {
    status: { title: "运行状态", icon: "i-grid", group: "工作台" },
    addons: { title: "附属插件中心", icon: "i-puzzle", group: "系统与扩展" },
};

const NAV_GROUPS = [
    ["工作台", ["status"]],
    ["机器人", ["bot", "server"]],
    ["群内体验", ["chat-format", "player-events", "server-notify", "motd", "markdown"]],
    ["安全与权限", ["admin", "binding", "whitelist", "filter-regex", "command-blacklist", "audit"]],
    ["AI 与指令", ["agent", "commands", "custom-commands"]],
    ["系统与扩展", ["addons", "features", "update-check", "placeholder-api"]],
];

const GENERAL_TITLES = {
    bot: "连接凭据",
    server: "基础设置",
    "chat-format": "转发模板",
    motd: "查询设置",
    markdown: "模板文件",
    whitelist: "命令模板",
    admin: "管理员判定",
    features: "功能开关",
    audit: "审核接口",
    agent: "模型接口",
    commands: "面板设置",
    "custom-commands": "命令列表",
    binding: "绑定策略",
    "command-blacklist": "禁止列表",
    "filter-regex": "过滤规则",
    "update-check": "更新检查",
    "placeholder-api": "占位符",
    "server-notify": "通知总开关",
};

const GROUP_TITLES = {
    join: "进服通知",
    quit: "退服通知",
    death: "死亡播报",
    shutdown: "关机通知",
    restart: "重启通知",
    crash: "意外关闭",
    context: "上下文与压缩",
};

const OPTION_LABELS = {
    "admin.mode": { both: "两者皆可", qq: "仅 QQ 群身份", config: "仅配置文件" },
    "agent.command-mode": { manual: "手动审批", auto: "自动执行" },
    "agent.think-visibility": { log: "仅写日志", group: "仅发 QQ 群", both: "两边都发" },
    "command-sender": { Hybrid: "Hybrid（混合）", Console: "Console（控制台）" },
};

function sectionTitle(key) {
    if (VIRTUAL_META[key]) return VIRTUAL_META[key].title;
    return state.sectionMap.get(key)?.title || key;
}

function navGroupOf(key) {
    for (const [label, keys] of NAV_GROUPS) {
        if (keys.includes(key)) return label;
    }
    return state.sectionMap.has(key) ? "其他" : "工作台";
}

function keyMeta(key) {
    return {
        title: sectionTitle(key),
        group: VIRTUAL_META[key]?.group || navGroupOf(key),
        icon: VIRTUAL_META[key]?.icon || SECTION_ICONS[key] || "i-file",
    };
}

function isKnown(key) {
    return !!key && (key === "status" || key === "addons" || state.sectionMap.has(key));
}

function readHash() {
    try {
        return decodeURIComponent(location.hash.replace(/^#\/?/, ""));
    } catch (_) {
        return "";
    }
}

function writeHash(key) {
    const next = key ? "#" + key : location.pathname + location.search;
    if (location.hash !== next) {
        history.replaceState(null, "", next);
    }
}

/* ───────────────────────── 登录 / 启动 ───────────────────────── */

function showLogin() {
    $("#main-view").classList.add("hidden");
    $("#login-view").classList.remove("hidden");
    $("#login-error").classList.add("hidden");
    $("#login-password").value = "";
    setTimeout(() => $("#login-password").focus(), 40);
}

function showMain() {
    $("#login-view").classList.add("hidden");
    $("#main-view").classList.remove("hidden");
}

function logout(options = {}) {
    state.token = "";
    state.schema = [];
    state.sectionMap.clear();
    state.values = {};
    state.groups = [];
    state.groupNames = {};
    state.groupNamesSettled = false;
    state.active = null;
    state.dirty = false;
    state.status = null;
    state.addonsData = null;
    state.addonQuery = "";
    state.addonPending = false;
    clearTimeout(state.addonSearchTimer);
    state.addonSearchTimer = null;
    clearInterval(state.qrTimer);
    state.qrTimer = null;
    state.qrSuccessHandled = false;
    state.loadedScope = "global";
    state.scope = "global";
    state.navSignature = "";
    localStorage.removeItem(TOKEN_KEY);
    $("#sidebar-platform").textContent = "正在连接…";
    clearTimeout(state.groupNameTimer);
    state.groupNameTimer = null;
    state.groupNamePolling = false;
    if ($("#qr-dialog").open) closeQr();
    if ($("#password-dialog").open) $("#password-dialog").close();
    $("#sidebar-nav").replaceChildren();
    $("#config-form").replaceChildren();
    showLogin();
    if (options.notify) toast("已退出登录", "success");
}

async function boot() {
    showMain();
    try {
        const data = await loadConfig(state.loadedScope || "global");
        state.platform = data.platform || "";
        renderNav();
        updateSidebarMeta();

        let key = readHash();
        if (!isKnown(key)) key = state.schema[0]?.key || "status";
        await activate(key, { force: true });
        loadStatus().catch(() => {});
        scheduleGroupNamePoll();
    } catch (error) {
        if (state.token) {
            toast(error.message, "error");
            showLogin();
        }
    }
}

async function doLogin(event) {
    event.preventDefault();
    const input = $("#login-password");
    const password = input.value;
    const errorEl = $("#login-error");
    errorEl.classList.add("hidden");
    if (!password) {
        errorEl.textContent = "请输入管理密码";
        errorEl.classList.remove("hidden");
        input.focus();
        return;
    }

    const btn = $("#login-btn");
    btn.classList.add("is-busy");
    btn.textContent = "正在登录…";
    try {
        const data = await request("/api/login", {
            method: "POST",
            body: { password },
            redirect: false,
        });
        state.token = data.token;
        localStorage.setItem(TOKEN_KEY, data.token);
        await boot();
    } catch (error) {
        errorEl.textContent = error.message === "未授权" ? "密码错误" : error.message;
        errorEl.classList.remove("hidden");
        input.select();
    } finally {
        btn.classList.remove("is-busy");
        btn.textContent = "进入控制台";
    }
}

function toggleSecret(input, btn) {
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    btn.setAttribute("aria-pressed", String(show));
    btn.setAttribute("aria-label", show ? "隐藏密码" : "显示密码");
    setIcon(btn.querySelector("svg"), show ? "i-eye-off" : "i-eye");
}

/* ───────────────────────── 配置加载 ───────────────────────── */

async function loadConfig(scope = "global") {
    const group = scope && scope !== "global" ? String(scope) : "";
    const query = group ? "?group=" + encodeURIComponent(group) : "";
    const data = await request("/api/config" + query);

    state.schema = Array.isArray(data.schema) ? data.schema : [];
    state.sectionMap = new Map(state.schema.map((section) => [section.key, section]));
    state.values = data.values || {};
    state.loadedScope = group || "global";
    state.platform = data.platform || state.platform || "";

    if (Array.isArray(data.groups)) {
        state.groups = data.groups
            .map((item) => (typeof item === "string" ? item : item?.openId))
            .filter(Boolean);
    }
    if (data.groupNames && typeof data.groupNames === "object") {
        for (const [openId, name] of Object.entries(data.groupNames)) {
            if (name) state.groupNames[openId] = name;
        }
    }
    return data;
}

function schemaSignature() {
    return state.schema.map((section) => section.key + ":" + section.title).join("|");
}

/* ───────────────────────── 导航渲染 ───────────────────────── */

function renderNav() {
    const signature = schemaSignature() + "|" + isKnown("addons");
    if (signature === state.navSignature) {
        updateNavActive();
        return;
    }
    state.navSignature = signature;

    const nav = $("#sidebar-nav");
    const fragment = document.createDocumentFragment();
    const covered = new Set();

    for (const [label, keys] of NAV_GROUPS) {
        const available = keys.filter((key) => isKnown(key));
        if (!available.length) continue;
        const group = h("div", "nav-group");
        group.appendChild(h("div", "nav-group-label", label));
        for (const key of available) {
            covered.add(key);
            group.appendChild(buildNavItem(key));
        }
        fragment.appendChild(group);
    }

    const rest = state.schema.map((section) => section.key).filter((key) => !covered.has(key));
    if (rest.length) {
        const group = h("div", "nav-group");
        group.appendChild(h("div", "nav-group-label", "其他"));
        for (const key of rest) group.appendChild(buildNavItem(key));
        fragment.appendChild(group);
    }

    nav.replaceChildren(fragment);
    updateNavActive();
}

function buildNavItem(key) {
    const meta = keyMeta(key);
    const btn = h("button", "nav-item");
    btn.type = "button";
    btn.dataset.key = key;
    btn.appendChild(icon(meta.icon));
    const label = h("span", "nav-label", meta.title);
    label.title = meta.title;
    btn.appendChild(label);
    btn.title = meta.title;
    if (key === state.active) btn.classList.add("active");
    return btn;
}

function updateNavActive() {
    let activeItem = null;
    $$(".nav-item").forEach((item) => {
        const active = item.dataset.key === state.active;
        item.classList.toggle("active", active);
        if (active) {
            item.setAttribute("aria-current", "page");
            activeItem = item;
        } else {
            item.removeAttribute("aria-current");
        }
    });
    if (!activeItem) return;
    const nav = $("#sidebar-nav");
    const navRect = nav.getBoundingClientRect();
    const itemRect = activeItem.getBoundingClientRect();
    if (itemRect.top < navRect.top + 4 || itemRect.bottom > navRect.bottom - 4) {
        nav.scrollTop += itemRect.top - navRect.top - Math.round(nav.clientHeight / 3);
    }
}

/* ───────────────────────── 路由 ───────────────────────── */

async function activate(key, { force = false } = {}) {
    if (!isKnown(key)) return;
    const seq = ++state.navSeq;

    if (!force && !(await confirmDiscard())) {
        writeHash(state.active || "");
        return;
    }
    if (seq !== state.navSeq) return;

    if (key === "commands" && state.scope !== "global" && !state.groups.includes(state.scope)) {
        state.scope = "global";
    }
    const desiredScope = key === "commands" ? (state.scope || "global") : "global";
    if (state.loadedScope !== desiredScope) {
        try {
            await loadConfig(desiredScope);
        } catch (error) {
            toast("加载配置失败：" + error.message, "error");
            return;
        }
    }
    if (seq !== state.navSeq) return;

    state.active = key;
    markDirty(false);
    closeSidebar();
    writeHash(key);
    updateChrome(key);
    updateNavActive();
    $("#content").scrollTop = 0;

    if (key === "status") {
        showPanel("status");
        renderStatusContent();
        loadStatus().catch((error) => {
            if (state.active !== "status" || state.status) return;
            const box = $("#status-content");
            const failed = h("div", "empty-panel");
            failed.appendChild(icon("i-alert"));
            failed.appendChild(h("span", null, "读取运行状态失败"));
            failed.appendChild(h("span", null, error.message));
            box.replaceChildren(failed);
        });
    } else if (key === "addons") {
        showPanel("config");
        renderAddons();
    } else {
        showPanel("config");
        renderSection(state.sectionMap.get(key));
    }
    updateSaveUi();
}

function updateChrome(key) {
    const meta = keyMeta(key);
    $("#crumb").textContent = meta.title;
    const eyebrow = $("#page-eyebrow");
    if (eyebrow) eyebrow.textContent = meta.group;
    document.title = "HuHoBot · " + meta.title;
}

function showPanel(which) {
    $("#status-panel").classList.toggle("hidden", which !== "status");
    $("#config-panel").classList.toggle("hidden", which !== "config");
}

function onHashChange() {
    const key = readHash();
    if (!isKnown(key) || key === state.active) return;
    activate(key);
}

/* ───────────────────────── 未保存提示 ───────────────────────── */

function markDirty(value) {
    state.dirty = !!value;
    updateSaveUi();
}

function updateSaveUi() {
    const isConfig = !!state.active && state.active !== "status" && state.active !== "addons"
        && state.sectionMap.has(state.active);
    const save = $("#save-btn");
    if (save) save.classList.toggle("hidden", !isConfig);
    $("#dirty-chip").classList.toggle("hidden", !(isConfig && state.dirty));
    $("#fab-save").classList.toggle("hidden", !(isConfig && state.dirty));
}

async function confirmDiscard() {
    if (!state.dirty) return true;
    return askConfirm({
        title: "放弃未保存的修改？",
        message: "当前页面还有尚未保存的更改，离开后这些修改会丢失。",
        okText: "放弃修改",
        danger: true,
    });
}

/* ───────────────────────── 配置分节渲染 ───────────────────────── */

function fieldGroupKey(section, field) {
    // 命令面板的「管理员命令可见性」与命令表同属一张卡片，不要拆成 command-panel 分组
    if (section.key === "commands") return "general";
    let rest = field.path;
    const prefix = section.key + ".";
    if (rest.startsWith(prefix)) rest = rest.slice(prefix.length);
    if (!rest.includes(".")) return "general";
    return rest.split(".")[0];
}

function groupTitle(sectionKey, groupKey) {
    if (groupKey === "general") return GENERAL_TITLES[sectionKey] || "基本设置";
    return GROUP_TITLES[groupKey] || groupKey;
}

function groupFields(section) {
    const map = new Map();
    for (const field of section.fields || []) {
        const key = fieldGroupKey(section, field);
        if (!map.has(key)) map.set(key, []);
        map.get(key).push(field);
    }
    return Array.from(map, ([key, fields]) => ({ key, title: groupTitle(section.key, key), fields }));
}

function renderSection(section) {
    if (!section) return;
    showPanel("config");
    $("#config-section-title").textContent = section.title || "配置";
    const desc = $("#config-section-desc");
    desc.textContent = section.description || "";
    desc.classList.toggle("hidden", !section.description);

    const form = $("#config-form");
    form.replaceChildren();
    form.dataset.section = section.key;

    if (section.key === "commands") form.appendChild(renderScopeCard(section));
    if (section.key === "bot") form.appendChild(renderQrCard());

    for (const group of groupFields(section)) {
        form.appendChild(renderSettingsCard(group));
    }
    markDirty(false);
}

function renderSettingsCard(group) {
    const card = h("section", "settings-card");
    const head = h("div", "card-head");
    const title = h("h2");
    title.appendChild(icon("i-sliders"));
    title.appendChild(h("span", null, group.title));
    head.appendChild(title);
    head.appendChild(h("span", "card-note", group.fields.length + " 项设置"));
    card.appendChild(head);
    for (const field of group.fields) {
        card.appendChild(renderFieldRow(field));
    }
    return card;
}

function renderFieldRow(field) {
    const type = field.type;
    const isControl = type === "boolean";
    const isBlock = type === "textarea" || type === "list" || type === "command-map"
        || type === "object-list" || type === "boolean-map";

    const row = h("div", "setting-row" + (isControl ? " is-control" : "") + (isBlock ? " is-block" : ""));
    row.dataset.fieldPath = field.path;

    const copy = h("div", "setting-copy");
    const id = "field-" + String(field.path).replace(/[^a-zA-Z0-9_-]/g, "-");
    const label = h(isBlock ? "div" : "label", "setting-label", field.label);
    if (!isBlock) label.htmlFor = id;
    copy.appendChild(label);
    if (field.description) copy.appendChild(h("p", "setting-desc", field.description));

    const control = h("div", "setting-control");
    control.appendChild(buildControl(field, collectValue(state.values, field), id));
    row.appendChild(copy);
    row.appendChild(control);
    return row;
}

/* ───────────────────────── 各类型控件 ───────────────────────── */

function buildControl(field, value, id) {
    switch (field.type) {
        case "text":
        case "password":
        case "number":
        case "textarea":
            return buildTextControl(field, value, id);
        case "boolean":
            return buildSwitch({
                id,
                path: field.path,
                checked: !!value,
                label: field.label,
            });
        case "select":
            return buildSelectControl(field, value, id);
        case "list":
            return buildListControl(field, value);
        case "boolean-map":
            return buildBooleanMapControl(field, value);
        case "command-map":
            return buildCommandMapControl(field, value);
        case "object-list":
            return buildObjectListControl(field, value);
        default:
            return buildTextControl({ ...field, type: "text" }, value, id);
    }
}

function buildTextControl(field, value, id) {
    if (field.type === "password") {
        const wrap = h("div", "input-wrap");
        wrap.appendChild(icon("i-key", "icon input-icon"));
        const input = h("input", "input has-icon");
        input.type = "password";
        input.id = id;
        input.dataset.path = field.path;
        input.dataset.type = "password";
        input.autocomplete = "off";
        if (field.placeholder) input.placeholder = field.placeholder;
        if (value != null) input.value = value;
        const action = h("button", "icon-btn input-action");
        action.type = "button";
        action.setAttribute("aria-label", "显示密码");
        action.setAttribute("aria-pressed", "false");
        action.appendChild(icon("i-eye"));
        action.addEventListener("click", () => toggleSecret(input, action));
        wrap.appendChild(input);
        wrap.appendChild(action);
        return wrap;
    }

    const multiline = field.type === "textarea";
    const input = h(multiline ? "textarea" : "input", "input");
    if (!multiline) {
        input.type = field.type === "number" ? "number" : "text";
        if (field.type === "number") input.inputMode = "numeric";
    } else {
        input.rows = 3;
    }
    input.id = id;
    input.dataset.path = field.path;
    input.dataset.type = field.type;
    if (field.placeholder) input.placeholder = field.placeholder;
    if (value != null) input.value = value;
    return input;
}

function buildSwitch({ id, path, checked, label, name }) {
    const wrap = h("label", "switch");
    const input = h("input");
    input.type = "checkbox";
    input.checked = !!checked;
    if (id) input.id = id;
    if (path) input.dataset.path = path;
    input.dataset.type = "boolean";
    if (name) input.dataset.name = name;
    input.setAttribute("aria-label", label || "开关");
    const track = h("span", "switch-track");
    wrap.appendChild(input);
    wrap.appendChild(track);
    return wrap;
}

function buildSelectControl(field, value, id) {
    const wrap = h("div", "select-wrap");
    const select = h("select", "input");
    select.id = id;
    select.dataset.path = field.path;
    select.dataset.type = "select";
    const labels = OPTION_LABELS[field.path] || {};
    for (const optionValue of field.options || []) {
        const option = h("option", null, labels[optionValue] || optionValue);
        option.value = optionValue;
        if (String(value) === String(optionValue)) option.selected = true;
        select.appendChild(option);
    }
    wrap.appendChild(select);
    return wrap;
}

/* 标签式列表 */
function buildListControl(field, value) {
    const items = Array.isArray(value) ? value.map(String) : [];
    const isGroupList = field.path === "bot.groups";
    const container = h("div", "tag-editor");
    container.dataset.path = field.path;
    container.dataset.type = "list";

    const addRow = h("div", "tag-add");
    const input = h("input", "input");
    input.dataset.transient = "1";
    input.placeholder = field.placeholder || "输入后回车添加";
    const addBtn = button("btn btn-ghost btn-sm", "添加", "i-plus");
    addRow.appendChild(input);
    addRow.appendChild(addBtn);
    container.appendChild(addRow);

    function renderTags() {
        $$(".tag, .tag-empty", container).forEach((node) => node.remove());
        if (!items.length) {
            const empty = h("span", "tag-empty", "尚未添加");
            container.insertBefore(empty, addRow);
        }
        items.forEach((item, index) => {
            const tag = h("span", "tag");
            tag.title = "点击复制：" + item;
            const label = h("span", "tag-label");
            label.dataset.value = item;
            tag.appendChild(label);
            if (isGroupList) {
                tag.dataset.groupChip = item;
                fillGroupChip(tag, item);
            } else {
                label.textContent = item;
            }
            label.addEventListener("click", () => copyText(item));

            const remove = h("button", "tag-remove", "×");
            remove.type = "button";
            remove.setAttribute("aria-label", "移除 " + item);
            remove.addEventListener("click", () => {
                items.splice(index, 1);
                markDirty(true);
                renderTags();
            });
            tag.appendChild(remove);
            container.insertBefore(tag, addRow);
        });
    }

    function addItem() {
        const next = input.value.trim();
        if (!next) return;
        if (!items.includes(next)) {
            items.push(next);
            markDirty(true);
            renderTags();
        } else {
            toast("该条目已存在", "info", 1600);
        }
        input.value = "";
        input.focus();
    }

    addBtn.addEventListener("click", addItem);
    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault();
            addItem();
        }
    });

    renderTags();
    return container;
}

function fillGroupChip(tag, openId) {
    const label = tag.querySelector(".tag-label");
    if (!label) return;
    const name = state.groupNames[openId];
    label.textContent = name || (state.groupNamesSettled ? "群聊" : "正在获取");
    const suffix = h("span", "tag-suffix", "…" + String(openId).slice(-6));
    label.appendChild(suffix);
}

/* 布尔映射 */
function buildBooleanMapControl(field, value) {
    const container = h("div", "boolean-list");
    container.dataset.path = field.path;
    container.dataset.type = "boolean-map";
    const entries = Object.entries(value || {});
    if (!entries.length) {
        container.appendChild(h("div", "tag-empty", "暂无开关项，保存后自动生成。"));
        return container;
    }
    for (const [name, enabled] of entries) {
        const item = h("div", "boolean-item");
        item.appendChild(h("span", null, name));
        const switchWrap = buildSwitch({
            checked: !!enabled,
            name,
            label: name + " 开关",
        });
        item.appendChild(switchWrap);
        container.appendChild(item);
    }
    return container;
}

/* 命令表 */
function buildCommandMapControl(field, value) {
    const container = h("div", "command-map");
    container.dataset.path = field.path;
    container.dataset.type = "command-map";
    const entries = Object.entries(value || {}).sort(([a], [b]) => a.localeCompare(b, "zh-CN"));

    const toolbar = h("div", "cmd-toolbar");
    const search = h("div", "cmd-search");
    search.appendChild(icon("i-search"));
    const searchInput = h("input", "input");
    searchInput.type = "search";
    searchInput.placeholder = "搜索命令…";
    searchInput.dataset.transient = "1";
    searchInput.setAttribute("aria-label", "搜索命令");
    search.appendChild(searchInput);
    const count = h("span", "cmd-count", entries.length + " 条命令");
    toolbar.appendChild(search);
    toolbar.appendChild(count);
    container.appendChild(toolbar);

    const table = h("div", "cmd-table");
    const head = h("div", "cmd-head");
    for (const label of ["命令", "启用", "面板", "优先级"]) head.appendChild(h("span", null, label));
    table.appendChild(head);

    const rowsBox = h("div", "cmd-rows");
    const rows = [];
    for (const [name, settings] of entries) {
        const row = h("div", "cmd-row");
        row.dataset.name = name;

        const nameEl = h("span", "cmd-name");
        nameEl.appendChild(h("span", null, name));
        if (name === "agent") nameEl.appendChild(h("span", "cmd-kind", "AI"));
        row.appendChild(nameEl);

        row.appendChild(buildCommandSwitch(name, "enable", "启用", !!settings.enable));
        row.appendChild(buildCommandSwitch(name, "pushMenu", "显示在面板", !!settings.pushMenu));

        const priority = h("input", "input");
        priority.type = "number";
        priority.min = "0";
        priority.max = "999";
        priority.step = "1";
        priority.dataset.setting = "priority";
        priority.value = safeNumber(settings.priority, 100);
        priority.setAttribute("aria-label", name + " 优先级");
        priority.title = "数字越小越靠前（0-999）";
        row.appendChild(priority);

        rows.push(row);
        rowsBox.appendChild(row);
    }
    table.appendChild(rowsBox);
    const empty = h("div", "cmd-empty", "没有匹配的命令");
    empty.hidden = true;
    table.appendChild(empty);
    container.appendChild(table);

    function filterRows() {
        const keyword = searchInput.value.trim().toLowerCase();
        let visible = 0;
        for (const row of rows) {
            const match = !keyword || row.dataset.name.toLowerCase().includes(keyword);
            row.hidden = !match;
            if (match) visible += 1;
        }
        empty.hidden = !(keyword && visible === 0);
        count.textContent = keyword ? visible + " / " + rows.length + " 条" : rows.length + " 条命令";
    }
    searchInput.addEventListener("input", filterRows);
    return container;
}

function buildCommandSwitch(name, setting, label, checked) {
    const wrap = h("label", "switch");
    const input = h("input");
    input.type = "checkbox";
    input.dataset.setting = setting;
    input.checked = !!checked;
    input.setAttribute("aria-label", name + " " + label);
    const track = h("span", "switch-track");
    wrap.appendChild(input);
    wrap.appendChild(track);
    return wrap;
}

/* 对象列表（自定义命令） */
function buildObjectListControl(field, value) {
    const container = h("div", "object-list");
    container.dataset.path = field.path;
    container.dataset.type = "object-list";
    const rows = Array.isArray(value) ? value.map((row) => ({ ...row })) : [];

    const addWrap = h("div", "object-add");
    const addBtn = button("btn btn-ghost btn-sm", "添加一行", "i-plus");
    addWrap.appendChild(addBtn);
    container.appendChild(addWrap);

    function renderRows() {
        $$(".object-row", container).forEach((node) => node.remove());
        rows.forEach((row, index) => {
            const box = h("div", "object-row");
            const fieldsBox = h("div", "object-fields");
            for (const sub of field.fields || []) {
                const col = h("div", "object-field");
                col.appendChild(h("span", "row-label", sub.label));
                if (sub.type === "boolean") {
                    const raw = row[sub.path];
                    col.appendChild(buildSwitch({
                        checked: raw == null ? true : raw === true || String(raw) === "true",
                        label: sub.label,
                        name: sub.path,
                    }));
                    const input = col.querySelector("input");
                    input.dataset.subpath = sub.path;
                } else {
                    const input = h("input", "input");
                    input.type = sub.type === "number" ? "number" : "text";
                    input.dataset.subpath = sub.path;
                    input.value = row[sub.path] ?? "";
                    if (sub.type === "number") input.inputMode = "numeric";
                    col.appendChild(input);
                }
                fieldsBox.appendChild(col);
            }
            box.appendChild(fieldsBox);

            const actions = h("div", "object-actions");
            const remove = button("btn btn-danger-ghost btn-sm", "删除", "i-trash");
            remove.addEventListener("click", () => {
                rows.splice(index, 1);
                markDirty(true);
                renderRows();
            });
            actions.appendChild(remove);
            box.appendChild(actions);
            container.insertBefore(box, addWrap);
        });
    }

    addBtn.addEventListener("click", () => {
        rows.push({});
        markDirty(true);
        renderRows();
    });

    renderRows();
    return container;
}

/* ───────────────────────── 特殊卡片 ───────────────────────── */

function renderQrCard() {
    const card = h("div", "special-card");
    const copy = h("div", "special-copy");
    const iconBox = h("div", "special-icon");
    iconBox.appendChild(icon("i-qr"));
    const text = h("div");
    text.appendChild(h("h2", null, "扫码连接机器人"));
    text.appendChild(h("p", null, "手机 QQ 扫码即可自动写入 AppID / Secret 并连接，无需手动填写。"));
    copy.appendChild(iconBox);
    copy.appendChild(text);
    card.appendChild(copy);

    const open = button("btn btn-primary", "打开扫码", "i-qr");
    open.addEventListener("click", openQr);
    card.appendChild(open);
    return card;
}

function groupLabel(openId) {
    const name = state.groupNames[openId];
    const suffix = String(openId).slice(-6);
    if (name) return name + "（…" + suffix + "）";
    return state.groupNamesSettled ? "群聊 …" + suffix : "正在获取群名…" + suffix;
}

function renderScopeCard(section) {
    let scope = state.scope || "global";
    if (scope !== "global" && !state.groups.includes(scope)) {
        scope = "global";
        state.scope = "global";
    }
    const isGroup = scope !== "global";
    const card = h("div", "scope-card");

    const top = h("div", "scope-top");
    const copy = h("div");
    copy.appendChild(h("h2", null, "配置范围"));
    copy.appendChild(h("p", null, "默认使用全局配置；为某个群保存后，该群将使用独立的指令面板与可用指令设置。"));
    top.appendChild(copy);
    card.appendChild(top);

    const controls = h("div", "scope-controls");
    const selectWrap = h("div", "select-wrap");
    const select = h("select", "input");
    select.id = "group-scope-select";
    select.dataset.transient = "1";
    select.setAttribute("aria-label", "选择配置范围");
    const globalOption = h("option", null, "全局配置（默认）");
    globalOption.value = "global";
    select.appendChild(globalOption);
    for (const openId of state.groups || []) {
        const option = h("option", null, groupLabel(openId));
        option.value = openId;
        select.appendChild(option);
    }
    select.value = isGroup ? scope : "global";
    selectWrap.appendChild(select);
    controls.appendChild(selectWrap);

    if (isGroup) {
        const reset = button("btn btn-ghost btn-sm", "恢复全局配置", "i-refresh");
        reset.title = "删除该群的覆盖配置，恢复使用全局设置";
        reset.addEventListener("click", () => resetGroupScope(section, scope));
        controls.appendChild(reset);

        const badge = h("span", state.values.__groupHasOverrides ? "badge-ok" : "badge-muted",
            state.values.__groupHasOverrides ? "已单独配置" : "使用全局默认");
        controls.appendChild(badge);
    }
    card.appendChild(controls);

    select.addEventListener("change", async () => {
        const next = select.value;
        if (next === state.scope) return;
        if (!(await confirmDiscard())) {
            select.value = state.scope || "global";
            return;
        }
        const previous = state.scope;
        state.scope = next;
        state.dirty = false;
        try {
            await loadConfig(next);
            if (state.active === "commands") renderSection(section);
        } catch (error) {
            state.scope = previous;
            select.value = previous;
            toast("加载群配置失败：" + error.message, "error");
        }
    });

    return card;
}

async function resetGroupScope(section, scope) {
    const ok = await askConfirm({
        title: "恢复全局配置？",
        message: "「" + groupLabel(scope) + "」的独立设置将被删除，重新使用全局配置。",
        okText: "恢复全局",
        danger: true,
    });
    if (!ok) return;
    try {
        await request("/api/group-config/reset", {
            method: "POST",
            body: { group: scope },
        });
        toast("已恢复全局配置", "success");
        await loadConfig(scope);
        if (state.active === "commands") renderSection(section);
    } catch (error) {
        toast("操作失败：" + error.message, "error");
    }
}

/* ───────────────────────── 收集与保存 ───────────────────────── */

function collectChanges() {
    const changes = {};
    const form = $("#config-form");
    const section = state.sectionMap.get(form.dataset.section);
    if (!section) return changes;

    for (const field of section.fields || []) {
        const nodes = form.querySelectorAll('[data-path="' + CSS.escape(field.path) + '"]');
        for (const node of nodes) {
            switch (node.dataset.type) {
                case "text":
                case "password":
                case "select":
                case "textarea":
                case undefined: {
                    if (node.type === "checkbox") break;
                    changes[field.path] = node.value;
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
                    node.querySelectorAll(".tag > .tag-label").forEach((label) => {
                        if (label.dataset.value !== undefined) items.push(label.dataset.value);
                    });
                    changes[field.path] = items;
                    break;
                }
                case "boolean-map": {
                    node.querySelectorAll("input[type=checkbox]").forEach((checkbox) => {
                        changes[field.path + "." + checkbox.dataset.name] = checkbox.checked;
                    });
                    break;
                }
                case "command-map": {
                    node.querySelectorAll(".cmd-row[data-name]").forEach((row) => {
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
                        rowEl.querySelectorAll("input[data-subpath]").forEach((input) => {
                            obj[input.dataset.subpath] = input.type === "checkbox"
                                ? input.checked
                                : input.type === "number"
                                    ? Number(input.value || 0)
                                    : input.value;
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
    if (!state.active || state.active === "status" || state.active === "addons") return;
    const section = state.sectionMap.get(state.active);
    if (!section) return;
    const changes = collectChanges();
    if (!Object.keys(changes).length) {
        toast("当前页面没有可保存的修改", "info");
        return;
    }

    const scope = section.key === "commands" ? (state.scope || "global") : "global";
    const query = scope !== "global" ? "?group=" + encodeURIComponent(scope) : "";
    const saveBtn = $("#save-btn");
    const fab = $("#fab-save");
    saveBtn.classList.add("is-busy");
    fab.classList.add("is-busy");

    try {
        await request("/api/config" + query, {
            method: "POST",
            body: { changes },
        });
        await loadConfig(scope);
        if (state.active === section.key) renderSection(section);
        markDirty(false);
        toast(scope === "global" ? "配置已保存并重载" : "群配置已保存", "success");
        loadStatus().catch(() => {});
    } catch (error) {
        toast("保存失败：" + error.message, "error");
    } finally {
        saveBtn.classList.remove("is-busy");
        fab.classList.remove("is-busy");
    }
}

/* ───────────────────────── 运行状态 ───────────────────────── */

function applyStatus(data) {
    state.status = data;
    state.platform = data.platform || state.platform;

    const groups = Array.isArray(data.groups) ? data.groups : [];
    const openIds = [];
    for (const group of groups) {
        const openId = typeof group === "string" ? group : group?.openId;
        if (!openId) continue;
        openIds.push(openId);
        const name = typeof group === "string" ? "" : group.name;
        if (name) state.groupNames[openId] = name;
    }
    state.groups = openIds;

    const connected = !!data.qqConnected;
    $("#conn-text").textContent = connected ? "QQ 已连接" : "QQ 未连接";
    $("#conn-pill .status-dot").className = "status-dot " + (connected ? "ok" : "bad");

    const dot = $("#sidebar-meta .status-dot");
    if (dot) dot.className = "status-dot " + (connected ? "ok" : "bad");
    updateSidebarMeta();

    state.groupNamesSettled = openIds.every((openId) => !!state.groupNames[openId]);
    refreshGroupChips();
    refreshScopeOptions();
    refreshStatusNames();
}

function updateSidebarMeta() {
    const status = state.status;
    const platform = status?.platform || state.platform || "";
    const version = status?.version || "";
    $("#sidebar-platform").textContent = platform
        ? platform + (version ? " · v" + version : "")
        : "正在连接…";
}

async function loadStatus() {
    if (state.statusLoading) return state.status;
    state.statusLoading = true;
    try {
        const data = await request("/api/status");
        if (!state.token) return data;
        applyStatus(data);
        if (state.active === "status") renderStatusContent();
        return data;
    } finally {
        state.statusLoading = false;
    }
}

function statCard(label, value, options = {}) {
    const card = h("div", "stat-card");
    const labelEl = h("div", "stat-label");
    labelEl.appendChild(icon(options.icon || "i-info"));
    labelEl.appendChild(h("span", null, label));
    card.appendChild(labelEl);
    const valueEl = h("div", "stat-value" + (options.tone ? " " + options.tone : ""));
    valueEl.appendChild(document.createTextNode(String(value)));
    if (options.unit) valueEl.appendChild(h("small", null, options.unit));
    card.appendChild(valueEl);
    return card;
}

function buildStatusHero(data, groups) {
    const hero = h("div", "status-hero");
    hero.appendChild(h("span", "brand-art"));

    const copy = h("div", "hero-copy");
    const title = h("h2", null, data.botName || "HuHoBot Penguin");
    const connected = !!data.qqConnected;
    title.appendChild(h("span", connected ? "badge-ok" : "badge-warn", connected ? "QQ 已连接" : "QQ 未连接"));
    if (data.agentEnabled) title.appendChild(h("span", "badge-ok", "Agent 已启用"));
    copy.appendChild(title);
    copy.appendChild(h("p", null,
        (data.serverName || "未命名服务器")
        + " · " + (data.platform || "-")
        + " · v" + (data.version || "-")));

    const meta = h("div", "hero-meta");
    meta.appendChild(h("span", "badge-muted", "AppID " + (data.appId || "未配置")));
    meta.appendChild(h("span", "badge-muted", groups.length + " 个群聊"));
    copy.appendChild(meta);
    hero.appendChild(copy);

    const actions = h("div", "hero-actions");
    const go = button("btn btn-ghost", "机器人配置", "i-bot", { dataset: { goto: "bot" } });
    actions.appendChild(go);
    hero.appendChild(actions);
    return hero;
}

function buildStatusStats(data, groups, online) {
    const grid = h("div", "stats-grid");
    grid.appendChild(statCard("在线玩家", online.length, { icon: "i-user", unit: "人", tone: online.length ? "ok" : "dim" }));
    grid.appendChild(statCard("绑定群聊", groups.length, { icon: "i-chat", unit: "个" }));
    grid.appendChild(statCard("QQ 连接", data.qqConnected ? "已连接" : "未连接", {
        icon: "i-power",
        tone: data.qqConnected ? "ok" : "warn",
    }));
    grid.appendChild(statCard("AI Agent", data.agentEnabled ? "已启用" : "未启用", {
        icon: "i-spark",
        tone: data.agentEnabled ? "ok" : "dim",
    }));
    return grid;
}

function buildGroupPanel(groups) {
    const card = h("div", "info-card");
    const head = h("div", "info-card-head");
    head.appendChild(h("h2", null, "绑定群聊"));
    head.appendChild(h("span", "badge-muted", groups.length + " 个"));
    card.appendChild(head);

    const body = h("div", "info-card-body");
    if (!groups.length) {
        const empty = h("div", "empty-panel");
        empty.appendChild(icon("i-chat"));
        empty.appendChild(h("span", null, "尚未绑定群聊"));
        empty.appendChild(h("span", null, "机器人会处理所有群消息，可在「QQ 机器人」里设置群列表。"));
        body.appendChild(empty);
        card.appendChild(body);
        return card;
    }

    const list = h("div", "group-list");
    for (const group of groups) {
        const openId = group.openId;
        const name = state.groupNames[openId] || group.name || "";
        const item = h("div", "group-item");
        item.dataset.openid = openId;
        const avatar = h("span", "group-avatar", name ? name.slice(0, 1) : "群");
        item.appendChild(avatar);
        const copy = h("div", "group-copy");
        const nameEl = h("div", "group-name", name || "群名称暂不可用");
        nameEl.title = name || openId;
        copy.appendChild(nameEl);
        copy.appendChild(h("div", "group-id", "…" + String(openId).slice(-6)));
        item.appendChild(copy);
        const copyBtn = button("icon-btn", "", "i-copy", { "aria-label": "复制群 OpenID" });
        copyBtn.title = "复制群 OpenID";
        copyBtn.addEventListener("click", () => copyText(openId));
        item.appendChild(copyBtn);
        list.appendChild(item);
    }
    body.appendChild(list);
    card.appendChild(body);
    return card;
}

function buildServerPanel(data) {
    const card = h("div", "info-card");
    const head = h("div", "info-card-head");
    head.appendChild(h("h2", null, "服务器信息"));
    card.appendChild(head);

    const body = h("div", "info-card-body");
    const rows = [
        ["平台", data.platform || "-"],
        ["版本", data.version ? "v" + data.version : "-"],
        ["服务器名称", data.serverName || "-"],
        ["机器人名称", data.botName || "-"],
        ["AppID", data.appId || "未配置"],
        ["Agent", data.agentEnabled ? "已启用" : "未启用"],
    ];
    for (const [key, value] of rows) {
        const row = h("div", "kv-row");
        row.appendChild(h("span", "kv-key", key));
        row.appendChild(h("span", "kv-val", value));
        body.appendChild(row);
    }
    card.appendChild(body);
    return card;
}

function buildOnlinePanel(online) {
    const card = h("div", "info-card");
    const head = h("div", "info-card-head");
    head.appendChild(h("h2", null, "在线玩家"));
    head.appendChild(h("span", "badge-muted", online.length + " 人"));
    card.appendChild(head);

    const body = h("div", "info-card-body");
    if (!online.length) {
        const empty = h("div", "empty-panel");
        empty.appendChild(icon("i-user"));
        empty.appendChild(h("span", null, "当前没有在线玩家"));
        body.appendChild(empty);
    } else {
        const list = h("div", "player-list");
        for (const name of online) {
            list.appendChild(h("span", "player-chip", String(name)));
        }
        body.appendChild(list);
    }
    card.appendChild(body);
    return card;
}

function renderStatusContent() {
    const box = $("#status-content");
    if (!state.status) {
        const loading = h("div", "empty-panel");
        loading.appendChild(icon("i-clock"));
        loading.appendChild(h("span", null, "正在读取运行状态…"));
        box.replaceChildren(loading);
        return;
    }

    const data = state.status;
    const groups = (Array.isArray(data.groups) ? data.groups : []).map((group) => {
        if (typeof group === "string") {
            return { openId: group, name: state.groupNames[group] || "", suffix: group.slice(-6) };
        }
        const openId = group?.openId || "";
        return {
            openId,
            name: state.groupNames[openId] || group?.name || "",
            suffix: group?.suffix || openId.slice(-6),
        };
    }).filter((group) => group.openId);
    const online = Array.isArray(data.online) ? data.online : [];

    const fragment = document.createDocumentFragment();
    fragment.appendChild(buildStatusHero(data, groups));
    fragment.appendChild(buildStatusStats(data, groups, online));
    const columns = h("div", "status-columns");
    columns.appendChild(buildGroupPanel(groups));
    columns.appendChild(buildServerPanel(data));
    fragment.appendChild(columns);
    fragment.appendChild(buildOnlinePanel(online));
    box.replaceChildren(fragment);
}

function refreshGroupChips() {
    $$("[data-group-chip]").forEach((tag) => fillGroupChip(tag, tag.dataset.groupChip));
}

function refreshScopeOptions() {
    const select = $("#group-scope-select");
    if (!select) return;
    for (const option of Array.from(select.options)) {
        if (option.value && option.value !== "global") {
            option.textContent = groupLabel(option.value);
        }
    }
}

function refreshStatusNames() {
    $$(".group-item[data-openid]").forEach((item) => {
        const openId = item.dataset.openid;
        const name = state.groupNames[openId];
        if (!name) return;
        const nameEl = item.querySelector(".group-name");
        const avatar = item.querySelector(".group-avatar");
        if (nameEl) nameEl.textContent = name;
        if (avatar) avatar.textContent = name.slice(0, 1);
    });
}

function scheduleGroupNamePoll() {
    if (!state.groups.length) return;
    if (state.groupNameTimer || state.groupNamePolling) return;
    if (state.groups.every((openId) => !!state.groupNames[openId])) {
        state.groupNamesSettled = true;
        refreshGroupChips();
        refreshScopeOptions();
        return;
    }

    let attempts = 0;
    const tick = async () => {
        if (state.groupNamePolling) return;
        state.groupNamePolling = true;
        attempts += 1;
        try {
            const data = await request("/api/status");
            applyStatus(data);
            if (state.active === "status") {
                // 状态页只更新群名，避免轮询导致整页重绘
                refreshStatusNames();
            }
        } catch (_) {
            // 网络抖动忽略，下一轮再试
        } finally {
            state.groupNamePolling = false;
        }
        const pending = state.groups.some((openId) => !state.groupNames[openId]);
        if (pending && attempts < 8) {
            state.groupNameTimer = setTimeout(tick, 1600);
        } else {
            state.groupNamesSettled = true;
            state.groupNameTimer = null;
            refreshGroupChips();
            refreshScopeOptions();
            refreshStatusNames();
        }
    };
    state.groupNameTimer = setTimeout(tick, 900);
}

/* ───────────────────────── 附属插件中心 ───────────────────────── */

function renderAddons() {
    showPanel("config");
    $("#config-section-title").textContent = "附属插件中心";
    const desc = $("#config-section-desc");
    desc.textContent = "从附属插件中心获取 Spigot / Paper 扩展。下载后需重启服务器才会生效，不做热加载。";
    desc.classList.remove("hidden");
    markDirty(false);

    const form = $("#config-form");
    form.replaceChildren();
    form.dataset.section = "addons";

    const toolbar = h("div", "addon-toolbar");
    const search = h("div", "addon-search");
    search.appendChild(icon("i-search"));
    const input = h("input", "input");
    input.type = "search";
    input.placeholder = "搜索插件名称或简介";
    input.value = state.addonQuery;
    input.dataset.transient = "1";
    input.setAttribute("aria-label", "搜索附属插件");
    search.appendChild(input);
    toolbar.appendChild(search);

    const refresh = button("btn btn-ghost btn-sm", "刷新", "i-refresh");
    toolbar.appendChild(refresh);

    const center = h("a", "btn btn-ghost btn-sm hidden");
    center.target = "_blank";
    center.rel = "noopener";
    center.appendChild(icon("i-external"));
    center.appendChild(h("span", null, "打开插件中心"));
    toolbar.appendChild(center);
    form.appendChild(toolbar);

    const grid = h("div", "addon-grid");
    grid.id = "addon-grid";
    form.appendChild(grid);

    if (state.addonsData) {
        renderAddonGrid(state.addonsData);
    } else {
        grid.appendChild(addonEmpty("正在加载附属插件…"));
    }

    input.addEventListener("input", () => {
        state.addonQuery = input.value.trim();
        clearTimeout(state.addonSearchTimer);
        state.addonSearchTimer = setTimeout(loadAddons, 350);
    });
    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault();
            clearTimeout(state.addonSearchTimer);
            state.addonQuery = input.value.trim();
            loadAddons();
        }
    });
    refresh.addEventListener("click", loadAddons);

    loadAddons();
    updateSaveUi();
}

function addonEmpty(text) {
    return h("div", "addon-empty", text);
}

async function loadAddons() {
    if (state.addonsLoading) {
        state.addonPending = true;
        return;
    }
    state.addonsLoading = true;
    const grid = $("#addon-grid");
    if (grid && !state.addonsData) grid.replaceChildren(addonEmpty("正在加载附属插件…"));

    const query = state.addonQuery ? "?search=" + encodeURIComponent(state.addonQuery) : "";
    try {
        const data = await request("/api/addons" + query);
        state.addonsData = data;
        if (state.active === "addons") renderAddonGrid(data);
    } catch (error) {
        if (grid && state.active === "addons") {
            grid.replaceChildren(addonEmpty("加载失败：" + error.message));
        }
    } finally {
        state.addonsLoading = false;
        if (state.addonPending) {
            state.addonPending = false;
            loadAddons();
        }
    }
}

function renderAddonGrid(data) {
    const grid = $("#addon-grid");
    if (!grid) return;
    const plugins = Array.isArray(data.plugins) ? data.plugins : [];
    const loaded = new Set((Array.isArray(data.installed) ? data.installed : [])
        .map((name) => String(name).toLowerCase()));
    const records = Array.isArray(data.installedRecords) ? data.installedRecords : [];
    const recordById = new Map(records.map((record) => [record.id, record]));
    const recordByName = new Map(records.map((record) => [String(record.name).toLowerCase(), record]));

    const center = $(".addon-toolbar a.btn");
    if (center && data.center) {
        center.href = data.center;
        center.classList.remove("hidden");
    }

    if (!plugins.length) {
        grid.replaceChildren(addonEmpty("没有找到符合条件的插件"));
        return;
    }

    const fragment = document.createDocumentFragment();
    for (const plugin of plugins) {
        const record = recordById.get(plugin.id) || recordByName.get(String(plugin.name).toLowerCase()) || null;
        const installed = loaded.has(String(plugin.name).toLowerCase()) || !!record;
        fragment.appendChild(buildAddonCard(plugin, installed, record));
    }
    grid.replaceChildren(fragment);
}

function buildAddonCard(plugin, installed, record) {
    const card = h("div", "addon-card");
    const head = h("div", "addon-head");
    const title = h("div", "addon-title");
    title.appendChild(h("span", null, plugin.name || "未命名插件"));
    if (installed) title.appendChild(h("span", "badge-ok", "已安装"));
    head.appendChild(title);
    head.appendChild(h("div", "addon-version", "v" + (plugin.version || "-")));
    card.appendChild(head);

    card.appendChild(h("p", "addon-desc", plugin.description || "暂无简介"));

    const meta = h("div", "addon-meta");
    const metaItems = [
        "作者：" + (plugin.author || "-"),
        "平台：" + (plugin.serverType || "-"),
        "大小：" + formatBytes(plugin.fileSize),
        "下载：" + (plugin.downloads ?? 0),
    ];
    if (record?.file) metaItems.push("文件：" + record.file);
    for (const item of metaItems) {
        const span = h("span", null, item);
        span.title = item;
        meta.appendChild(span);
    }
    card.appendChild(meta);

    const tags = Array.isArray(plugin.tags) ? plugin.tags : [];
    if (tags.length) {
        const tagBox = h("div", "addon-tags");
        for (const tag of tags) tagBox.appendChild(h("span", "addon-tag", String(tag)));
        card.appendChild(tagBox);
    }

    const actions = h("div", "addon-actions");
    const detail = button("btn btn-ghost btn-sm", "详情", "i-file");
    const install = button(
        "btn " + (installed ? "btn-ghost" : "btn-primary") + " btn-sm",
        installed ? "已安装" : "下载安装",
        installed ? "i-check" : "i-download",
    );
    install.disabled = installed;
    actions.appendChild(detail);
    actions.appendChild(install);
    if (record?.file) {
        const remove = button("btn btn-danger-ghost btn-sm", "删除", "i-trash");
        actions.appendChild(remove);
        remove.addEventListener("click", async () => {
            const ok = await askConfirm({
                title: "删除附属插件？",
                message: "将删除 " + record.file + "，需要重启服务器后生效。",
                okText: "删除",
                danger: true,
            });
            if (!ok) return;
            remove.classList.add("is-busy");
            try {
                await request("/api/addons/remove", {
                    method: "POST",
                    body: { file: record.file },
                });
                toast("已删除 " + record.file + "，重启服务器后生效", "success", 4200);
                await loadAddons();
            } catch (error) {
                toast("删除失败：" + error.message, "error");
            } finally {
                remove.classList.remove("is-busy");
            }
        });
    }
    card.appendChild(actions);

    const readme = h("pre", "addon-readme hidden");
    card.appendChild(readme);

    detail.addEventListener("click", () => {
        const opening = readme.classList.contains("hidden");
        readme.classList.toggle("hidden", !opening);
        if (opening && !readme.textContent) {
            readme.textContent = plugin.readme || "该插件没有提供说明文档。";
        }
        detail.querySelector("span").textContent = opening ? "收起" : "详情";
    });

    install.addEventListener("click", async () => {
        install.classList.add("is-busy");
        const text = install.querySelector("span");
        text.textContent = "下载中…";
        try {
            const data = await request("/api/addons/install", {
                method: "POST",
                body: { id: plugin.id },
            });
            toast("已下载 " + data.file + "，重启服务器后生效", "success", 4200);
            await loadAddons();
        } catch (error) {
            toast("下载失败：" + error.message, "error");
            text.textContent = "下载安装";
            install.classList.remove("is-busy");
        }
    });

    return card;
}

/* ───────────────────────── 扫码连接 ───────────────────────── */

async function openQr() {
    const dialog = $("#qr-dialog");
    state.qrSuccessHandled = false;
    if (!dialog.open) dialog.showModal();
    clearInterval(state.qrTimer);
    state.qrTimer = null;
    renderQrState({ supported: true, state: "idle", message: "正在准备授权…" });

    try {
        let data = await request("/api/qr/status");
        if (["idle", "cancelled", "failed", "success"].includes(data.state)) {
            await request("/api/qr/start", { method: "POST" });
            data = await request("/api/qr/status");
        }
        renderQrState(data);
        if (data.supported !== false && !["success", "failed", "cancelled"].includes(data.state)) {
            state.qrTimer = setInterval(pollQr, 2000);
        }
    } catch (error) {
        renderQrState({ supported: true, state: "failed", message: error.message });
    }
}

function closeQr() {
    clearInterval(state.qrTimer);
    state.qrTimer = null;
    const dialog = $("#qr-dialog");
    if (dialog.open) dialog.close();
}

async function pollQr() {
    try {
        renderQrState(await request("/api/qr/status"));
    } catch (_) {
        // 网络抖动忽略，下一轮再试
    }
}

async function retryQr() {
    try {
        await request("/api/qr/start", { method: "POST" });
        clearInterval(state.qrTimer);
        state.qrTimer = setInterval(pollQr, 2000);
        renderQrState(await request("/api/qr/status"));
    } catch (error) {
        toast(error.message, "error");
    }
}

async function cancelQr() {
    try {
        await request("/api/qr/cancel", { method: "POST" });
        renderQrState(await request("/api/qr/status"));
    } catch (error) {
        toast(error.message, "error");
    }
}

function onQrSuccess(message) {
    if (state.qrSuccessHandled) return;
    state.qrSuccessHandled = true;
    toast(message || "扫码授权成功，已写入凭据并连接", "success", 3200);
    loadConfig(state.loadedScope || "global").then(() => {
        if (state.active === "bot") {
            const section = state.sectionMap.get("bot");
            if (section) renderSection(section);
        }
    }).catch(() => {});
    loadStatus().catch(() => {});
    setTimeout(() => {
        if ($("#qr-dialog").open) closeQr();
    }, 1600);
}

function renderQrState(data) {
    const info = data || {};
    const img = $("#qr-image");
    const placeholder = $("#qr-placeholder");
    const placeholderText = placeholder.querySelector("span");
    const stateEl = $("#qr-state");
    const link = $("#qr-link");
    const cancelBtn = $("#qr-cancel-btn");
    const retryBtn = $("#qr-retry-btn");

    cancelBtn.classList.add("hidden");
    retryBtn.classList.add("hidden");
    stateEl.className = "qr-state";

    if (info.supported === false) {
        img.classList.add("hidden");
        placeholder.classList.remove("hidden");
        placeholderText.textContent = info.message || "当前平台不支持扫码授权";
        link.classList.add("hidden");
        clearInterval(state.qrTimer);
        state.qrTimer = null;
        return;
    }

    if (info.qrImage) {
        img.src = "data:image/png;base64," + info.qrImage;
        img.classList.remove("hidden");
        placeholder.classList.add("hidden");
    } else {
        img.classList.add("hidden");
        placeholder.classList.remove("hidden");
        placeholderText.textContent = ["waiting", "expired"].includes(info.state)
            ? "正在获取二维码…"
            : (info.message || "等待开始");
    }

    if (info.qrUrl) {
        link.href = info.qrUrl;
        link.classList.remove("hidden");
    } else {
        link.classList.add("hidden");
    }

    switch (info.state) {
        case "waiting":
            stateEl.textContent = info.message || "等待扫码授权…";
            cancelBtn.classList.remove("hidden");
            break;
        case "expired":
            stateEl.textContent = info.message || "二维码已过期，正在刷新…";
            stateEl.classList.add("warn");
            cancelBtn.classList.remove("hidden");
            break;
        case "success":
            stateEl.textContent = "授权成功，正在连接…";
            stateEl.classList.add("ok");
            clearInterval(state.qrTimer);
            state.qrTimer = null;
            onQrSuccess(info.message);
            break;
        case "failed":
            stateEl.textContent = info.message || "授权失败";
            stateEl.classList.add("err");
            retryBtn.classList.remove("hidden");
            clearInterval(state.qrTimer);
            state.qrTimer = null;
            break;
        case "cancelled":
            stateEl.textContent = "已取消扫码授权";
            retryBtn.classList.remove("hidden");
            clearInterval(state.qrTimer);
            state.qrTimer = null;
            break;
        case "idle":
        default:
            stateEl.textContent = info.message || "尚未开始";
            retryBtn.classList.remove("hidden");
            break;
    }
}

/* ───────────────────────── 修改密码 ───────────────────────── */

function openPasswordDialog() {
    const dialog = $("#password-dialog");
    $("#password-new").value = "";
    $("#password-confirm").value = "";
    $("#password-error").classList.add("hidden");
    if (!dialog.open) dialog.showModal();
    setTimeout(() => $("#password-new").focus(), 40);
}

async function submitPassword(event) {
    event.preventDefault();
    const next = $("#password-new").value;
    const confirm = $("#password-confirm").value;
    const errorEl = $("#password-error");
    errorEl.classList.add("hidden");

    if (next.length < 6) {
        errorEl.textContent = "密码需至少 6 位";
        errorEl.classList.remove("hidden");
        return;
    }
    if (next !== confirm) {
        errorEl.textContent = "两次输入的密码不一致";
        errorEl.classList.remove("hidden");
        return;
    }

    const btn = $("#password-save-btn");
    btn.classList.add("is-busy");
    try {
        await request("/api/password", {
            method: "POST",
            body: { newPassword: next },
        });
        $("#password-dialog").close();
        toast("密码已更新，请使用新密码重新登录", "success", 3600);
        setTimeout(() => logout(), 700);
    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove("hidden");
    } finally {
        btn.classList.remove("is-busy");
    }
}

/* ───────────────────────── 确认框 ───────────────────────── */

let confirmResolver = null;

function askConfirm({ title, message, okText = "确定", danger = false }) {
    return new Promise((resolve) => {
        if (confirmResolver) {
            const previous = confirmResolver;
            confirmResolver = null;
            previous(false);
        }
        const dialog = $("#confirm-dialog");
        $("#confirm-title").textContent = title || "确认操作";
        $("#confirm-message").textContent = message || "";
        const ok = $("#confirm-ok-btn");
        ok.textContent = okText;
        ok.className = "btn " + (danger ? "btn-danger" : "btn-primary");
        confirmResolver = resolve;
        if (!dialog.open) dialog.showModal();
    });
}

function finishConfirm(value) {
    const resolver = confirmResolver;
    confirmResolver = null;
    const dialog = $("#confirm-dialog");
    if (dialog.open) dialog.close();
    if (resolver) resolver(value);
}

/* ───────────────────────── 移动端侧栏 ───────────────────────── */

function openSidebar() {
    $("#main-view").classList.add("nav-open");
    $("#sidebar-scrim").hidden = false;
    document.body.classList.add("no-scroll");
}

function closeSidebar() {
    $("#main-view").classList.remove("nav-open");
    $("#sidebar-scrim").hidden = true;
    document.body.classList.remove("no-scroll");
}

/* ───────────────────────── 初始化 ───────────────────────── */

function bindEvents() {
    $("#login-form").addEventListener("submit", doLogin);
    $("#login-eye").addEventListener("click", () => {
        toggleSecret($("#login-password"), $("#login-eye"));
    });

    $("#sidebar-nav").addEventListener("click", (event) => {
        const item = event.target.closest(".nav-item");
        if (item) activate(item.dataset.key);
    });

    $("#save-btn").addEventListener("click", saveConfig);
    $("#fab-save").addEventListener("click", saveConfig);
    $("#status-btn").addEventListener("click", () => activate("status"));
    $("#conn-pill").addEventListener("click", () => activate("status"));
    $("#status-refresh-btn").addEventListener("click", async () => {
        const btn = $("#status-refresh-btn");
        btn.classList.add("is-busy");
        try {
            await loadStatus();
        } catch (error) {
            toast("刷新失败：" + error.message, "error");
        } finally {
            btn.classList.remove("is-busy");
        }
    });

    $("#logout-btn").addEventListener("click", async () => {
        const ok = await askConfirm({
            title: "退出登录？",
            message: "退出后需要重新输入管理密码才能进入控制台。",
            okText: "退出",
            danger: true,
        });
        if (ok) logout({ notify: true });
    });

    $("#password-btn").addEventListener("click", openPasswordDialog);
    $("#password-form").addEventListener("submit", submitPassword);
    $("#password-close-btn").addEventListener("click", () => $("#password-dialog").close());
    $("#password-cancel-btn").addEventListener("click", () => $("#password-dialog").close());

    $("#qr-close-btn").addEventListener("click", closeQr);
    $("#qr-cancel-btn").addEventListener("click", cancelQr);
    $("#qr-retry-btn").addEventListener("click", retryQr);
    $("#qr-dialog").addEventListener("close", () => {
        clearInterval(state.qrTimer);
        state.qrTimer = null;
    });
    $("#qr-dialog").addEventListener("click", (event) => {
        if (event.target === $("#qr-dialog")) closeQr();
    });

    $("#confirm-ok-btn").addEventListener("click", () => finishConfirm(true));
    $("#confirm-cancel-btn").addEventListener("click", () => finishConfirm(false));
    $("#confirm-close-btn").addEventListener("click", () => finishConfirm(false));
    $("#confirm-dialog").addEventListener("close", () => {
        if (confirmResolver) {
            const resolver = confirmResolver;
            confirmResolver = null;
            resolver(false);
        }
    });

    $("#menu-btn").addEventListener("click", openSidebar);
    $("#sidebar-close").addEventListener("click", closeSidebar);
    $("#sidebar-scrim").addEventListener("click", closeSidebar);

    $("#config-form").addEventListener("input", (event) => {
        if (event.target.closest("[data-transient]")) return;
        if (event.target.matches("input, textarea, select")) markDirty(true);
    });
    $("#config-form").addEventListener("change", (event) => {
        if (event.target.closest("[data-transient]")) return;
        if (event.target.matches("input, textarea, select")) markDirty(true);
    });

    $("#status-content").addEventListener("click", (event) => {
        const copy = event.target.closest("[data-copy]");
        if (copy) {
            copyText(copy.dataset.copy);
            return;
        }
        const goto = event.target.closest("[data-goto]");
        if (goto) activate(goto.dataset.goto);
    });

    window.addEventListener("hashchange", onHashChange);
    window.addEventListener("resize", () => {
        if (window.innerWidth > 980) closeSidebar();
    });
    document.addEventListener("keydown", (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
            if (document.querySelector("dialog[open]")) return;
            if (state.active && state.active !== "status" && state.active !== "addons") {
                event.preventDefault();
                saveConfig();
            }
        }
        if (event.key === "Escape") closeSidebar();
    });

    window.addEventListener("beforeunload", (event) => {
        if (state.dirty) {
            event.preventDefault();
            event.returnValue = "";
        }
    });
}

function init() {
    bindEvents();
    if (state.token) {
        boot();
    } else {
        showLogin();
    }
}

document.addEventListener("DOMContentLoaded", init);
