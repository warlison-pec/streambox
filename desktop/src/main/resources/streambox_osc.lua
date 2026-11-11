-- Controles do player do StreamBox (substituem os controles padrão do mpv).
-- Desenhados pelo próprio mpv por cima do vídeo, com o visual do app: preto e laranja.
-- Conversa com o app por script-message:
--   app -> script: sb-info <vod|live> <título> <subtítulo>
--   script -> app: sb-voltar, sb-esc, sb-tela-cheia, sb-canal <+1|-1>

local mp = require "mp"

local ORANGE = "&H007AFF&"   -- #FF7A00 em BGR
local WHITE = "&HFFFFFF&"
local MUTED = "&HB4B4B4&"
local BLACK = "&H000000&"

local info = { kind = "vod", title = "", subtitle = "" }
local ov = mp.create_osd_overlay("ass-events")
local last_mouse = { x = -1, y = -1 }
local last_activity = 0
local boxes = {}
local HIDE_AFTER = 3

local LANGS = {
    por = "Português", pob = "Português", pt = "Português", ["pt-BR"] = "Português", ["pt-PT"] = "Português",
    eng = "Inglês", en = "Inglês", spa = "Espanhol", es = "Espanhol", jpn = "Japonês", ja = "Japonês",
    fre = "Francês", fra = "Francês", fr = "Francês", ger = "Alemão", deu = "Alemão", de = "Alemão",
    ita = "Italiano", it = "Italiano", kor = "Coreano", ko = "Coreano", chi = "Chinês", zho = "Chinês",
}

local function esc(s)
    s = tostring(s or "")
    return (s:gsub("\\", "/"):gsub("{", "("):gsub("}", ")"):gsub("\n", " "))
end

local function now() return mp.get_time() end

local function fmt_time(t)
    if not t or t < 0 then t = 0 end
    t = math.floor(t)
    local h, m, s = math.floor(t / 3600), math.floor(t % 3600 / 60), t % 60
    if h > 0 then return string.format("%d:%02d:%02d", h, m, s) end
    return string.format("%d:%02d", m, s)
end

local function track_label(kind)
    local t = mp.get_property_native("current-tracks/" .. kind)
    if not t then return kind == "sub" and "Desligada" or "—" end
    local name = LANGS[t.lang or ""] or (t.lang and t.lang:upper()) or nil
    local title = t.title
    if name and title and not title:lower():find(name:lower(), 1, true) then
        return name .. " · " .. title
    end
    return name or title or ("Faixa " .. tostring(t.id))
end

local function count_tracks(kind)
    local n = 0
    for _, t in ipairs(mp.get_property_native("track-list") or {}) do
        if t.type == kind then n = n + 1 end
    end
    return n
end

-- ---------- desenho ----------

-- string.format com todos os números arredondados (o %d do Lua 5.3+ não aceita frações)
local unpack_args = unpack or table.unpack

local function F(fmt, ...)
    local n = select("#", ...)
    local args = { ... }
    for i = 1, n do
        if type(args[i]) == "number" then args[i] = math.floor(args[i] + 0.5) end
    end
    return string.format(fmt, unpack_args(args, 1, n))
end

local function rect(x1, y1, x2, y2, color, alpha)
    return F("{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H%s&\\p1}m %d %d l %d %d %d %d %d %d{\\p0}",
        color, alpha or "00", x1, y1, x2, y1, x2, y2, x1, y2)
end

-- Retângulo de cantos arredondados (aproximado com 8 pontos)
local function rrect(x1, y1, x2, y2, r, color, alpha)
    r = math.min(r, (x2 - x1) / 2, (y2 - y1) / 2)
    local c = r * 0.3
    return F(
        "{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H%s&\\p1}m %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d l %d %d b %d %d %d %d %d %d{\\p0}",
        color, alpha or "00",
        x1 + r, y1, x2 - r, y1, x2 - c, y1, x2, y1 + c, x2, y1 + r,
        x2, y2 - r, x2, y2 - c, x2 - c, y2, x2 - r, y2,
        x1 + r, y2, x1 + c, y2, x1, y2 - c, x1, y2 - r,
        x1, y1 + r, x1, y1 + c, x1 + c, y1, x1 + r, y1)
end

local function circle(cx, cy, r, color, alpha)
    local k = r * 0.5523
    return F(
        "{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H%s&\\p1}m %d %d b %d %d %d %d %d %d b %d %d %d %d %d %d b %d %d %d %d %d %d b %d %d %d %d %d %d{\\p0}",
        color, alpha or "00",
        cx, cy - r,
        cx + k, cy - r, cx + r, cy - k, cx + r, cy,
        cx + r, cy + k, cx + k, cy + r, cx, cy + r,
        cx - k, cy + r, cx - r, cy + k, cx - r, cy,
        cx - r, cy - k, cx - k, cy - r, cx, cy - r)
end

local function poly(points, color, alpha)
    local s = F("{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H%s&\\p1}m %d %d", color, alpha or "00", points[1], points[2])
    for i = 3, #points, 2 do s = s .. F(" l %d %d", points[i], points[i + 1]) end
    return s .. "{\\p0}"
end

local function text(x, y, an, size, color, str, bold)
    return F("{\\an%d\\pos(%d,%d)\\fs%d\\bord0\\shad1\\4c&H000000&\\4a&H80&\\1c%s\\b%d}%s",
        an, x, y, size, color, bold and 1 or 0, esc(str))
end

local function add_box(x1, y1, x2, y2, action)
    boxes[#boxes + 1] = { x1 = x1, y1 = y1, x2 = x2, y2 = y2, action = action }
end

local function hovered(x1, y1, x2, y2)
    return last_mouse.x >= x1 and last_mouse.x <= x2 and last_mouse.y >= y1 and last_mouse.y <= y2
end

-- Botão redondo com ícone desenhado
local function icon_button(out, cx, cy, r, icon, action, primary)
    local hot = hovered(cx - r, cy - r, cx + r, cy + r)
    local bg = primary and ORANGE or (hot and "&H2A2A2A&" or "&H1E1E1E&")
    out[#out + 1] = circle(cx, cy, r, bg, primary and "00" or "30")
    if hot and not primary then out[#out + 1] = circle(cx, cy, r, ORANGE, "B0") end
    local fg = primary and BLACK or WHITE
    local u = r / 22
    if icon == "back" then
        out[#out + 1] = poly({ cx - 9*u, cy, cx + 3*u, cy - 12*u, cx + 7*u, cy - 8*u, cx - 1*u, cy, cx + 7*u, cy + 8*u, cx + 3*u, cy + 12*u }, fg)
    elseif icon == "play" then
        out[#out + 1] = poly({ cx - 7*u, cy - 11*u, cx + 11*u, cy, cx - 7*u, cy + 11*u }, fg)
    elseif icon == "pause" then
        out[#out + 1] = rect(cx - 9*u, cy - 10*u, cx - 3*u, cy + 10*u, fg)
        out[#out + 1] = rect(cx + 3*u, cy - 10*u, cx + 9*u, cy + 10*u, fg)
    elseif icon == "rew" or icon == "fwd" then
        local d = icon == "rew" and -1 or 1
        out[#out + 1] = poly({ cx - d*9*u, cy - 9*u, cx + d*1*u, cy, cx - d*9*u, cy + 9*u }, fg)
        out[#out + 1] = poly({ cx - d*1*u, cy - 9*u, cx + d*9*u, cy, cx - d*1*u, cy + 9*u }, fg)
    elseif icon == "up" or icon == "down" then
        local d = icon == "up" and -1 or 1
        out[#out + 1] = poly({ cx - 10*u, cy - d*5*u, cx, cy + d*7*u, cx + 10*u, cy - d*5*u }, fg)
    elseif icon == "full" then
        local a, b = 10*u, 4*u
        out[#out + 1] = poly({ cx - a, cy - a, cx - a + 2*b, cy - a, cx - a + 2*b, cy - a + b, cx - a + b, cy - a + b, cx - a + b, cy - a + 2*b, cx - a, cy - a + 2*b }, fg)
        out[#out + 1] = poly({ cx + a, cy + a, cx + a - 2*b, cy + a, cx + a - 2*b, cy + a - b, cx + a - b, cy + a - b, cx + a - b, cy + a - 2*b, cx + a, cy + a - 2*b }, fg)
        out[#out + 1] = poly({ cx + a, cy - a, cx + a, cy - a + 2*b, cx + a - b, cy - a + 2*b, cx + a - b, cy - a + b, cx + a - 2*b, cy - a + b, cx + a - 2*b, cy - a }, fg)
        out[#out + 1] = poly({ cx - a, cy + a, cx - a, cy + a - 2*b, cx - a + b, cy + a - 2*b, cx - a + b, cy + a - b, cx - a + 2*b, cy + a - b, cx - a + 2*b, cy + a }, fg)
    end
    add_box(cx - r, cy - r, cx + r, cy + r, action)
end

-- Botão com texto (áudio, legenda)
local function text_button(out, x, cy, h, label, action, size)
    local w = math.floor(#label * size * 0.52 + 36)
    local hot = hovered(x, cy - h / 2, x + w, cy + h / 2)
    out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, hot and "&H2A2A2A&" or "&H1E1E1E&", "30")
    if hot then out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, ORANGE, "B0") end
    out[#out + 1] = text(x + w / 2, cy, 5, size, WHITE, label, true)
    add_box(x, cy - h / 2, x + w, cy + h / 2, action)
    return w
end

local function visible()
    if mp.get_property_bool("pause") then return true end
    return now() - last_activity < HIDE_AFTER
end

local function render()
    boxes = {}
    local w, h = mp.get_osd_size()
    if not w or w == 0 then return end
    ov.res_x, ov.res_y = w, h
    if not visible() then
        if ov.data ~= "" then ov.data = ""; ov:update() end
        return
    end
    local s = math.max(0.75, math.min(h / 1080 * 1.35, 1.6))   -- escala dos controles
    local out = {}
    local pad = math.floor(40 * s)
    local live = info.kind == "live"

    -- Topo: voltar + título
    local top = math.floor(110 * s)
    out[#out + 1] = rect(0, 0, w, top, BLACK, "60")
    local br = math.floor(26 * s)
    icon_button(out, pad + br, math.floor(top / 2), br, "back", "back")
    local tx = pad + 2 * br + math.floor(20 * s)
    if info.subtitle ~= "" then
        out[#out + 1] = text(tx, math.floor(top / 2 - 14 * s), 4, math.floor(34 * s), WHITE, info.title, true)
        out[#out + 1] = text(tx, math.floor(top / 2 + 22 * s), 4, math.floor(24 * s), MUTED, info.subtitle)
    else
        out[#out + 1] = text(tx, math.floor(top / 2), 4, math.floor(34 * s), WHITE, info.title, true)
    end

    -- Base: barra de progresso + botões
    local bottom_h = math.floor(170 * s)
    local by = h - bottom_h
    out[#out + 1] = rect(0, by, w, h, BLACK, "60")
    local bar_y = by + math.floor(45 * s)
    local row_y = h - math.floor(55 * s)
    local pos = mp.get_property_number("time-pos", 0)
    local dur = mp.get_property_number("duration", 0)

    if live then
        out[#out + 1] = rrect(pad, bar_y - math.floor(16 * s), pad + math.floor(130 * s), bar_y + math.floor(16 * s), math.floor(16 * s), ORANGE)
        out[#out + 1] = text(pad + math.floor(65 * s), bar_y, 5, math.floor(22 * s), BLACK, "● AO VIVO", true)
    elseif dur and dur > 0 then
        local x1, x2 = pad, w - pad
        local bh = math.floor(6 * s)
        local hot = hovered(x1, bar_y - 18 * s, x2, bar_y + 18 * s)
        if hot then bh = math.floor(10 * s) end
        local px = x1 + (x2 - x1) * math.min(1, pos / dur)
        out[#out + 1] = rrect(x1, bar_y - bh / 2, x2, bar_y + bh / 2, bh / 2, WHITE, "B0")
        out[#out + 1] = rrect(x1, bar_y - bh / 2, px, bar_y + bh / 2, bh / 2, ORANGE)
        out[#out + 1] = circle(px, bar_y, math.floor((hot and 11 or 8) * s), ORANGE)
        out[#out + 1] = text(x1, bar_y + math.floor(30 * s), 4, math.floor(24 * s), WHITE, fmt_time(pos))
        out[#out + 1] = text(x2, bar_y + math.floor(30 * s), 6, math.floor(24 * s), MUTED, fmt_time(dur))
        if hot and last_mouse.x >= x1 then
            local t = dur * (last_mouse.x - x1) / (x2 - x1)
            out[#out + 1] = text(last_mouse.x, bar_y - math.floor(24 * s), 2, math.floor(22 * s), ORANGE, fmt_time(t), true)
        end
        add_box(x1, bar_y - 18 * s, x2, bar_y + 18 * s, "seekbar")
    end

    -- Botões da esquerda
    local r = math.floor(24 * s)
    local x = pad + r
    local paused = mp.get_property_bool("pause")
    if live then
        icon_button(out, x, row_y, r, "down", "ch-down"); x = x + 2 * r + math.floor(14 * s)
        icon_button(out, x, row_y, math.floor(r * 1.2), paused and "play" or "pause", "pause", true); x = x + math.floor(2.2 * r) + math.floor(14 * s)
        icon_button(out, x, row_y, r, "up", "ch-up")
    else
        icon_button(out, x, row_y, r, "rew", "rew"); x = x + 2 * r + math.floor(14 * s)
        icon_button(out, x, row_y, math.floor(r * 1.2), paused and "play" or "pause", "pause", true); x = x + math.floor(2.2 * r) + math.floor(14 * s)
        icon_button(out, x, row_y, r, "fwd", "fwd")
    end

    -- Botões da direita: tela cheia, legenda, áudio
    local fx = w - pad - r
    icon_button(out, fx, row_y, r, "full", "full")
    local size = math.floor(22 * s)
    local bh = math.floor(44 * s)
    local labels = {}
    if count_tracks("sub") > 0 then labels[#labels + 1] = { "Legenda: " .. track_label("sub"), "sub" } end
    if count_tracks("audio") > 1 then labels[#labels + 1] = { "Áudio: " .. track_label("audio"), "audio" } end
    local rx = fx - r - math.floor(14 * s)
    for _, l in ipairs(labels) do
        local tw = math.floor(#l[1] * size * 0.52 + 36)
        rx = rx - tw
        text_button(out, rx, row_y, bh, l[1], l[2], size)
        rx = rx - math.floor(12 * s)
    end

    ov.data = table.concat(out, "\n")
    ov:update()
end

-- ---------- ações ----------

local function show_track(kind)
    local label = track_label(kind)
    mp.osd_message((kind == "sub" and "Legenda: " or "Áudio: ") .. label, 2)
end

local function act(action)
    if action == "back" then mp.commandv("script-message", "sb-voltar")
    elseif action == "pause" then mp.commandv("cycle", "pause")
    elseif action == "rew" then mp.commandv("seek", "-10")
    elseif action == "fwd" then mp.commandv("seek", "10")
    elseif action == "ch-up" then mp.commandv("script-message", "sb-canal", "1")
    elseif action == "ch-down" then mp.commandv("script-message", "sb-canal", "-1")
    elseif action == "full" then mp.commandv("script-message", "sb-tela-cheia")
    elseif action == "audio" then mp.commandv("cycle", "aid"); show_track("audio")
    elseif action == "sub" then mp.commandv("cycle", "sid"); show_track("sub")
    elseif action == "seekbar" then
        for _, b in ipairs(boxes) do
            if b.action == "seekbar" then
                local pct = (last_mouse.x - b.x1) / (b.x2 - b.x1) * 100
                mp.commandv("seek", tostring(math.max(0, math.min(100, pct))), "absolute-percent")
            end
        end
    end
end

local function poke()
    last_activity = now()
    render()
end

local function on_click()
    local x, y = mp.get_mouse_pos()
    last_mouse.x, last_mouse.y = x, y
    if visible() then
        for i = #boxes, 1, -1 do
            local b = boxes[i]
            if x >= b.x1 and x <= b.x2 and y >= b.y1 and y <= b.y2 then
                act(b.action)
                poke()
                return
            end
        end
        -- clique no vídeo: pausa/continua (no ao vivo só mostra os controles)
        if info.kind ~= "live" then mp.commandv("cycle", "pause") end
    end
    poke()
end

-- Movimento do mouse mostra os controles
mp.add_periodic_timer(0.1, function()
    local x, y = mp.get_mouse_pos()
    if x ~= last_mouse.x or y ~= last_mouse.y then
        last_mouse.x, last_mouse.y = x, y
        last_activity = now()
    end
    render()
end)

mp.add_forced_key_binding("MBTN_LEFT", "sb-click", on_click)
mp.add_forced_key_binding("MBTN_LEFT_DBL", "sb-dblclick", function() mp.commandv("script-message", "sb-tela-cheia") end)
mp.add_forced_key_binding("ESC", "sb-esc", function() mp.commandv("script-message", "sb-esc") end)
mp.add_forced_key_binding("BS", "sb-back", function() mp.commandv("script-message", "sb-voltar") end)
mp.add_forced_key_binding("f", "sb-full", function() mp.commandv("script-message", "sb-tela-cheia") end)
mp.add_forced_key_binding("q", "sb-quit", function() mp.commandv("script-message", "sb-voltar") end)
mp.add_forced_key_binding("j", "sb-sub", function() act("sub"); poke() end)
mp.add_forced_key_binding("#", "sb-audio", function() act("audio"); poke() end)

mp.register_script_message("sb-info", function(kind, title, subtitle)
    info.kind = kind or "vod"
    info.title = title or ""
    info.subtitle = subtitle or ""
    poke()
end)

mp.observe_property("pause", "bool", function() poke() end)
mp.register_event("file-loaded", poke)
