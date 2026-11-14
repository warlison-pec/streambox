-- Controles do player do StreamBox (substituem os controles padrão do mpv).
-- Desenhados pelo próprio mpv por cima do vídeo, com o visual do app: preto e laranja.
--
-- O mouse NÃO vem do mpv: a área de vídeo é da janela do app (no Windows o mpv desativa a própria janela
-- quando está embutido, então nunca recebe cliques). O app captura o mouse e repassa por script-message.
--
-- app -> script:
--   sb-info <vod|live> <título> <subtítulo> <tem episódios: 0|1>
--   sb-mouse|sb-click|sb-dbl <x> <y> <largura> <altura>   (coordenadas da área de vídeo no app)
--   sb-wheel <+1|-1>, sb-leave
--   sb-menu <json>   (abre/atualiza um menu: {title, group, left, right, sel, items:[{label, detail, checked, cmd, keep}]})
--   sb-close-menu
--   sb-segmentos <json [{type, start, end}] em segundos>, sb-proximo <rótulo do próximo episódio ou vazio>
-- script -> app:
--   sb-voltar, sb-esc, sb-tela-cheia, sb-canal <+1|-1>, sb-abrir <legendas|audio|episodios|canais>,
--   sb-escolha <args do item...>, sb-menu-aberto <0|1>, sb-controles <0|1>, sb-proximo-agora, sb-digito <0-9>

local mp = require "mp"
local utils = require "mp.utils"

local ORANGE = "&H007AFF&"   -- #FF7A00 em BGR
local WHITE = "&HFFFFFF&"
local MUTED = "&HB4B4B4&"
local BLACK = "&H000000&"
local PANEL = "&H141414&"

local info = { kind = "vod", title = "", subtitle = "", episodes = false }
local ov = mp.create_osd_overlay("ass-events")
local mouse = { x = -1, y = -1, inside = false }
local last_activity = 0
local boxes = {}
local HIDE_AFTER = 3
local last_data = nil
local controls_shown = nil

local menu = nil          -- menu aberto
local menu_geom = nil     -- {x1, y1, x2, y2, rows = {{y1, y2, index}}, row_h, visible_rows}

local segments = {}
local next_label = nil
local next_dismissed = false
local next_deadline = nil
local last_tick = nil

local video_click = nil   -- {t, toggled} último clique no vídeo (para desfazer a pausa no duplo clique)

local LANGS = {
    por = "Português", pob = "Português", pt = "Português", ["pt-BR"] = "Português", ["pt-PT"] = "Português",
    eng = "Inglês", en = "Inglês", spa = "Espanhol", es = "Espanhol", jpn = "Japonês", ja = "Japonês",
    fre = "Francês", fra = "Francês", fr = "Francês", ger = "Alemão", deu = "Alemão", de = "Alemão",
    ita = "Italiano", it = "Italiano", kor = "Coreano", ko = "Coreano", chi = "Chinês", zho = "Chinês",
}

local SKIP_LABELS = {
    Intro = "Pular abertura", Recap = "Pular resumo", Preview = "Pular prévia",
    Commercial = "Pular comercial", Outro = "Pular créditos",
}

-- ---------- utilidades ----------

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

-- Tamanho em caracteres (UTF-8) e corte com reticências
local function ulen(s)
    local _, n = tostring(s or ""):gsub("[^\128-\191]", "")
    return n
end

local function ucut(s, max)
    s = tostring(s or "")
    if ulen(s) <= max then return s end
    local out, n = {}, 0
    for ch in s:gmatch("[%z\1-\127\194-\244][\128-\191]*") do
        n = n + 1
        if n > max - 1 then break end
        out[#out + 1] = ch
    end
    return table.concat(out) .. "…"
end

local function track_label(kind)
    local t = mp.get_property_native("current-tracks/" .. kind)
    if not t then return kind == "sub" and "Desligadas" or "—" end
    local name = LANGS[t.lang or ""] or (t.lang and t.lang:upper()) or nil
    return name or t.title or ("Faixa " .. tostring(t.id))
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

-- Retângulo de cantos arredondados (aproximado com curvas)
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

local function add_box(x1, y1, x2, y2, action, arg)
    boxes[#boxes + 1] = { x1 = x1, y1 = y1, x2 = x2, y2 = y2, action = action, arg = arg }
end

local function hovered(x1, y1, x2, y2)
    return mouse.inside and mouse.x >= x1 and mouse.x <= x2 and mouse.y >= y1 and mouse.y <= y2
end

-- Botão redondo com ícone desenhado
local function icon_button(out, cx, cy, r, icon, action, primary)
    local hot = hovered(cx - r, cy - r, cx + r, cy + r)
    local bg = primary and ORANGE or (hot and "&H2A2A2A&" or "&H1E1E1E&")
    out[#out + 1] = circle(cx, cy, r, bg, primary and "00" or "30")
    if hot and not primary then out[#out + 1] = circle(cx, cy, r, ORANGE, "B0") end
    if hot and primary then out[#out + 1] = circle(cx, cy, r, WHITE, "C8") end
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
    elseif icon == "vol" or icon == "mute" then
        -- alto-falante
        out[#out + 1] = rect(cx - 11*u, cy - 4*u, cx - 5*u, cy + 4*u, fg)
        out[#out + 1] = poly({ cx - 6*u, cy - 4*u, cx + 2*u, cy - 11*u, cx + 2*u, cy + 11*u, cx - 6*u, cy + 4*u }, fg)
        if icon == "mute" then
            -- X ao lado
            out[#out + 1] = poly({ cx + 5*u, cy - 6*u, cx + 7*u, cy - 6*u, cx + 13*u, cy + 4*u, cx + 11*u, cy + 6*u }, fg)
            out[#out + 1] = poly({ cx + 11*u, cy - 6*u, cx + 13*u, cy - 4*u, cx + 7*u, cy + 6*u, cx + 5*u, cy + 4*u }, fg)
        else
            -- ondas do som
            out[#out + 1] = rect(cx + 5*u, cy - 4*u, cx + 7*u, cy + 4*u, fg)
            out[#out + 1] = rect(cx + 9*u, cy - 8*u, cx + 11*u, cy + 8*u, fg)
        end
    end
    add_box(cx - r, cy - r, cx + r, cy + r, action)
end

local function pill_width(label, size)
    return math.floor(ulen(label) * size * 0.56 + size * 1.6)
end

-- Botão com texto (legendas, áudio, episódios…)
local function text_button(out, x, cy, h, label, action, size, primary)
    local w = pill_width(label, size)
    local hot = hovered(x, cy - h / 2, x + w, cy + h / 2)
    if primary then
        out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, ORANGE)
        if hot then out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, WHITE, "C8") end
    else
        out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, hot and "&H2A2A2A&" or "&H1E1E1E&", "30")
        if hot then out[#out + 1] = rrect(x, cy - h / 2, x + w, cy + h / 2, h / 2, ORANGE, "B0") end
    end
    out[#out + 1] = text(x + w / 2, cy, 5, size, primary and BLACK or WHITE, label, true)
    add_box(x, cy - h / 2, x + w, cy + h / 2, action)
    return w
end

-- Volume: botão de mudo + barra (clique escolhe o nível; a roda do mouse e as teclas + / - também ajustam)
local function volume_control(out, x, cy, r, s)
    local muted = mp.get_property_bool("mute")
    local vol = mp.get_property_number("volume", 100)
    icon_button(out, x + r, cy, r, muted and "mute" or "vol", "mute")
    local x1 = x + 2 * r + math.floor(12 * s)
    local x2 = x1 + math.floor(130 * s)
    local hot = hovered(x1 - 6 * s, cy - 18 * s, x2 + 6 * s, cy + 18 * s)
    local bh = math.floor((hot and 8 or 6) * s)
    local px = x1 + (x2 - x1) * math.max(0, math.min(1, vol / 100))
    local color = muted and MUTED or ORANGE
    out[#out + 1] = rrect(x1, cy - bh / 2, x2, cy + bh / 2, bh / 2, WHITE, "B0")
    if px > x1 then out[#out + 1] = rrect(x1, cy - bh / 2, px, cy + bh / 2, bh / 2, color) end
    out[#out + 1] = circle(px, cy, math.floor((hot and 9 or 7) * s), color)
    local label = muted and "Mudo" or (math.floor(vol + 0.5) .. "%")
    out[#out + 1] = text(x2 + math.floor(14 * s), cy, 4, math.floor(20 * s), hot and ORANGE or MUTED, label, hot)
    add_box(x1 - 6 * s, cy - 18 * s, x2 + 6 * s, cy + 18 * s, "volbar", { x1 = x1, x2 = x2 })
end

local function show_volume()
    if mp.get_property_bool("mute") then mp.osd_message("Sem som", 1); return end
    mp.osd_message("Volume: " .. math.floor(mp.get_property_number("volume", 100) + 0.5) .. "%", 1)
end

local function visible()
    if menu then return true end
    if mp.get_property_bool("pause") then return true end
    if not mouse.inside and now() - last_activity > 0.8 then return false end
    return now() - last_activity < HIDE_AFTER
end

-- ---------- trechos (pular abertura) e próximo episódio ----------

local function current_segment(pos)
    for _, sg in ipairs(segments) do
        local skip_outro_ok = sg.type ~= "Outro" or not next_label or next_dismissed
        if pos >= sg.start and pos < sg["end"] - 1 and skip_outro_ok and SKIP_LABELS[sg.type] then return sg end
    end
    return nil
end

local function in_next_zone(pos, dur)
    if not next_label or next_dismissed or not dur or dur <= 0 then return false end
    for _, sg in ipairs(segments) do
        if sg.type == "Outro" then return pos >= sg.start end
    end
    return dur - pos <= 30 and dur > 300
end

local frame_dt = 0

local function draw_overlays(out, w, h, s, controls)
    if info.kind == "live" or menu then return end
    local pos = mp.get_property_number("time-pos", 0)
    local dur = mp.get_property_number("duration", 0)
    local paused = mp.get_property_bool("pause")
    local margin = math.floor(48 * s)
    local base_y = h - (controls and math.floor(200 * s) or margin)

    local dt = frame_dt

    if in_next_zone(pos, dur) then
        if not next_deadline then next_deadline = 10 end
        if not paused then next_deadline = next_deadline - dt end
        if next_deadline <= 0 then
            next_deadline = nil
            next_dismissed = true
            mp.commandv("script-message", "sb-proximo-agora")
            return
        end
        local cw, ch = math.floor(520 * s), math.floor(200 * s)
        local x2, y2 = w - margin, base_y
        local x1, y1 = x2 - cw, y2 - ch
        out[#out + 1] = rrect(x1, y1, x2, y2, math.floor(18 * s), PANEL, "10")
        out[#out + 1] = text(x1 + 28 * s, y1 + 34 * s, 4, math.floor(18 * s), ORANGE, "PRÓXIMO EPISÓDIO", true)
        out[#out + 1] = text(x1 + 28 * s, y1 + 72 * s, 4, math.floor(28 * s), WHITE, ucut(next_label, 30), true)
        local cd = paused and "Pausado" or ("Começa em " .. math.ceil(next_deadline) .. " s")
        out[#out + 1] = text(x2 - 28 * s, y1 + 34 * s, 6, math.floor(20 * s), MUTED, cd)
        local by = y2 - math.floor(46 * s)
        local size = math.floor(22 * s)
        local bh = math.floor(52 * s)
        local bw = text_button(out, x1 + 28 * s, by, bh, "Assistir agora", "next-now", size, true)
        text_button(out, x1 + 28 * s + bw + 16 * s, by, bh, "Cancelar", "next-cancel", size)
        return
    end
    next_deadline = nil

    local sg = current_segment(pos)
    if sg then
        local size = math.floor(26 * s)
        local bh = math.floor(60 * s)
        local label = SKIP_LABELS[sg.type]
        local bw = pill_width(label, size)
        text_button(out, w - margin - bw, base_y - bh / 2, bh, label, "skip", size, true)
        boxes[#boxes].arg = sg["end"]
    end
end

-- ---------- menu (lista de opções à direita, como os diálogos do app Android) ----------

local function menu_rows_area(h, s)
    local top = math.floor(40 * s)
    local head = math.floor((menu.group and 150 or 100) * s)
    return top, head
end

local function ensure_visible()
    if not menu or not menu_geom then return end
    local n = menu_geom.visible_rows
    if menu.sel < menu.scroll + 1 then menu.scroll = menu.sel - 1 end
    if menu.sel > menu.scroll + n then menu.scroll = menu.sel - n end
    menu.scroll = math.max(0, math.min(menu.scroll, math.max(0, #menu.items - n)))
end

local function draw_menu(out, w, h, s)
    local pw = math.min(math.floor(620 * s), w - math.floor(40 * s))
    local margin = math.floor(24 * s)
    local x2, x1 = w - margin, w - margin - pw
    local y1, y2 = margin, h - margin
    local row_h = math.floor(58 * s)
    local top, head = menu_rows_area(h, s)
    local list_y = y1 + head
    local visible_rows = math.max(1, math.floor((y2 - list_y - 20 * s) / row_h))
    menu_geom = { x1 = x1, y1 = y1, x2 = x2, y2 = y2, rows = {}, row_h = row_h, visible_rows = visible_rows }
    ensure_visible()

    out[#out + 1] = rect(0, 0, w, h, BLACK, "90")
    out[#out + 1] = rrect(x1, y1, x2, y2, math.floor(20 * s), PANEL, "08")
    local pad = math.floor(30 * s)
    out[#out + 1] = text(x1 + pad, y1 + 50 * s, 4, math.floor(32 * s), WHITE, menu.title or "", true)
    -- fechar
    local cr = math.floor(22 * s)
    local ccx, ccy = x2 - pad - cr, y1 + 50 * s
    local hot = hovered(ccx - cr, ccy - cr, ccx + cr, ccy + cr)
    out[#out + 1] = circle(ccx, ccy, cr, hot and ORANGE or "&H2A2A2A&", "00")
    out[#out + 1] = text(ccx, ccy, 5, math.floor(24 * s), hot and BLACK or WHITE, "✕", true)
    menu_geom.close = { ccx - cr, ccy - cr, ccx + cr, ccy + cr }

    if menu.group then
        local gy = y1 + 108 * s
        local ar = math.floor(20 * s)
        menu_geom.left, menu_geom.right = nil, nil
        if menu.left then
            local lx = x1 + pad + ar
            local lh = hovered(lx - ar, gy - ar, lx + ar, gy + ar)
            out[#out + 1] = circle(lx, gy, ar, lh and ORANGE or "&H2A2A2A&")
            out[#out + 1] = text(lx, gy, 5, math.floor(20 * s), lh and BLACK or WHITE, "◀", true)
            menu_geom.left = { lx - ar, gy - ar, lx + ar, gy + ar }
        end
        if menu.right then
            local rx = x2 - pad - ar
            local rh = hovered(rx - ar, gy - ar, rx + ar, gy + ar)
            out[#out + 1] = circle(rx, gy, ar, rh and ORANGE or "&H2A2A2A&")
            out[#out + 1] = text(rx, gy, 5, math.floor(20 * s), rh and BLACK or WHITE, "▶", true)
            menu_geom.right = { rx - ar, gy - ar, rx + ar, gy + ar }
        end
        out[#out + 1] = text((x1 + x2) / 2, gy, 5, math.floor(24 * s), ORANGE, ucut(menu.group, 34), true)
    end

    local size = math.floor(24 * s)
    local dsize = math.floor(19 * s)
    local max_chars = math.floor((pw - 2 * pad - 50 * s) / (size * 0.52))
    local last = math.min(#menu.items, menu.scroll + visible_rows)
    for i = menu.scroll + 1, last do
        local it = menu.items[i]
        local ry1 = list_y + (i - menu.scroll - 1) * row_h
        local ry2 = ry1 + row_h - math.floor(4 * s)
        local enabled = it.cmd ~= nil
        local hot_row = enabled and hovered(x1 + 12 * s, ry1, x2 - 12 * s, ry2)
        if enabled and (hot_row or i == menu.sel) then
            out[#out + 1] = rrect(x1 + 12 * s, ry1, x2 - 12 * s, ry2, math.floor(12 * s), hot_row and "&H2A2A2A&" or "&H222222&")
            if i == menu.sel then out[#out + 1] = rect(x1 + 12 * s, ry1 + 10 * s, x1 + 17 * s, ry2 - 10 * s, ORANGE) end
        end
        local cy = (ry1 + ry2) / 2
        local tx = x1 + pad + math.floor(36 * s)
        if it.checked then
            out[#out + 1] = circle(x1 + pad + 10 * s, cy, math.floor(9 * s), ORANGE)
        end
        local color = (not enabled) and MUTED or (it.checked and ORANGE or WHITE)
        if it.detail and it.detail ~= "" then
            out[#out + 1] = text(tx, cy - 11 * s, 4, size, color, ucut(it.label, max_chars), it.checked)
            out[#out + 1] = text(tx, cy + 15 * s, 4, dsize, MUTED, ucut(it.detail, math.floor(max_chars * 1.2)))
        else
            out[#out + 1] = text(tx, cy, 4, size, color, ucut(it.label, max_chars), it.checked)
        end
        menu_geom.rows[#menu_geom.rows + 1] = { y1 = ry1, y2 = ry2, index = i }
    end
    -- barra de rolagem
    if #menu.items > visible_rows then
        local th = y2 - list_y - 20 * s
        local bh = math.max(30 * s, th * visible_rows / #menu.items)
        local by = list_y + (th - bh) * menu.scroll / (#menu.items - visible_rows)
        out[#out + 1] = rrect(x2 - 10 * s, by, x2 - 5 * s, by + bh, 3 * s, ORANGE, "40")
    end
end

-- ---------- tela ----------

local function render()
    local t = now()
    frame_dt = last_tick and math.min(t - last_tick, 1) or 0
    last_tick = t
    boxes = {}
    local w, h = mp.get_osd_size()
    if not w or w == 0 then return end
    ov.res_x, ov.res_y = w, h
    local s = math.max(0.6, math.min(h / 1080 * 1.2, 1.6))   -- escala dos controles
    local out = {}
    local show = visible()
    if show ~= controls_shown then
        controls_shown = show
        mp.commandv("script-message", "sb-controles", show and "1" or "0")
    end

    if menu then
        draw_menu(out, w, h, s)
    elseif show then
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
            -- trechos marcados (abertura/créditos) na barra
            for _, sg in ipairs(segments) do
                local sx1 = x1 + (x2 - x1) * math.max(0, math.min(1, sg.start / dur))
                local sx2 = x1 + (x2 - x1) * math.max(0, math.min(1, sg["end"] / dur))
                if sx2 > sx1 then out[#out + 1] = rect(sx1, bar_y - bh / 2, sx2, bar_y + bh / 2, WHITE, "70") end
            end
            out[#out + 1] = rrect(x1, bar_y - bh / 2, px, bar_y + bh / 2, bh / 2, ORANGE)
            out[#out + 1] = circle(px, bar_y, math.floor((hot and 11 or 8) * s), ORANGE)
            out[#out + 1] = text(x1, bar_y + math.floor(30 * s), 4, math.floor(24 * s), WHITE, fmt_time(pos))
            out[#out + 1] = text(x2, bar_y + math.floor(30 * s), 6, math.floor(24 * s), MUTED, fmt_time(dur))
            if hot then
                local t = dur * (mouse.x - x1) / (x2 - x1)
                out[#out + 1] = text(mouse.x, bar_y - math.floor(24 * s), 2, math.floor(22 * s), ORANGE, fmt_time(t), true)
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
        volume_control(out, x + r + math.floor(24 * s), row_y, r, s)

        -- Botões da direita: tela cheia, legendas, áudio, episódios/canais
        local fx = w - pad - r
        icon_button(out, fx, row_y, r, "full", "full")
        local size = math.floor(22 * s)
        local bh = math.floor(44 * s)
        local labels = {}
        labels[#labels + 1] = { "Legendas · " .. track_label("sub"), "menu-legendas" }
        if count_tracks("audio") > 0 then labels[#labels + 1] = { "Áudio · " .. track_label("audio"), "menu-audio" } end
        if info.episodes then labels[#labels + 1] = { "Episódios", "menu-episodios" } end
        if live then labels[#labels + 1] = { "Canais", "menu-canais" } end
        local rx = fx - r - math.floor(14 * s)
        for _, l in ipairs(labels) do
            rx = rx - pill_width(l[1], size)
            text_button(out, rx, row_y, bh, l[1], l[2], size)
            rx = rx - math.floor(12 * s)
        end
    end

    draw_overlays(out, w, h, s, show)

    local data = table.concat(out, "\n")
    if data ~= last_data then
        last_data = data
        ov.data = data
        ov:update()
    end
end

-- ---------- ações ----------

local function poke()
    last_activity = now()
    render()
end

local bind_menu_keys   -- definida mais abaixo

local function close_menu()
    if not menu then return end
    bind_menu_keys(false)
    menu = nil
    menu_geom = nil
    mp.commandv("script-message", "sb-menu-aberto", "0")
    poke()
end

local function send_cmd(cmd)
    local args = { "script-message", "sb-escolha" }
    for _, a in ipairs(cmd) do args[#args + 1] = tostring(a) end
    mp.commandv(unpack_args(args))
end

local function choose(i)
    local it = menu and menu.items[i]
    if not it or not it.cmd then return end
    if not it.keep then close_menu() end
    send_cmd(it.cmd)
end

local function move_sel(d)
    if not menu or #menu.items == 0 then return end
    local i = menu.sel
    for _ = 1, #menu.items do
        i = math.max(1, math.min(#menu.items, i + d))
        if menu.items[i].cmd or i == 1 or i == #menu.items then break end
    end
    menu.sel = i
    ensure_visible()
    poke()
end

local function act(action, arg)
    if action == "back" then mp.commandv("script-message", "sb-voltar")
    elseif action == "pause" then mp.commandv("cycle", "pause")
    elseif action == "rew" then mp.commandv("seek", "-10"); mp.osd_message("-10 s", 1)
    elseif action == "fwd" then mp.commandv("seek", "10"); mp.osd_message("+10 s", 1)
    elseif action == "ch-up" then mp.commandv("script-message", "sb-canal", "1")
    elseif action == "ch-down" then mp.commandv("script-message", "sb-canal", "-1")
    elseif action == "full" then mp.commandv("script-message", "sb-tela-cheia")
    elseif action == "menu-legendas" then mp.commandv("script-message", "sb-abrir", "legendas")
    elseif action == "menu-audio" then mp.commandv("script-message", "sb-abrir", "audio")
    elseif action == "menu-episodios" then mp.commandv("script-message", "sb-abrir", "episodios")
    elseif action == "menu-canais" then mp.commandv("script-message", "sb-abrir", "canais")
    elseif action == "mute" then
        mp.commandv("cycle", "mute"); show_volume()
    elseif action == "volbar" and arg then
        -- além de 100% só pela roda do mouse ou pelo teclado (até 130%)
        local v = math.floor(math.max(0, math.min(1, (mouse.x - arg.x1) / (arg.x2 - arg.x1))) * 100 + 0.5)
        mp.set_property_number("volume", v)
        mp.set_property_bool("mute", false)
        show_volume()
    elseif action == "skip" then mp.commandv("seek", tostring(arg), "absolute")
    elseif action == "next-now" then
        next_dismissed = true; next_deadline = nil
        mp.commandv("script-message", "sb-proximo-agora")
    elseif action == "next-cancel" then next_dismissed = true; next_deadline = nil
    elseif action == "seekbar" then
        for _, b in ipairs(boxes) do
            if b.action == "seekbar" then
                local pct = (mouse.x - b.x1) / (b.x2 - b.x1) * 100
                mp.commandv("seek", tostring(math.max(0, math.min(100, pct))), "absolute-percent")
            end
        end
    end
end

local function inside(b, x, y)
    return b and x >= b[1] and x <= b[3] and y >= b[2] and y <= b[4]
end

local function menu_click(x, y)
    local g = menu_geom
    if not g then return end
    if x < g.x1 or x > g.x2 or y < g.y1 or y > g.y2 or inside(g.close, x, y) then close_menu(); return end
    if inside(g.left, x, y) and menu.left then send_cmd(menu.left); return end
    if inside(g.right, x, y) and menu.right then send_cmd(menu.right); return end
    for _, r in ipairs(g.rows) do
        if y >= r.y1 and y <= r.y2 then
            menu.sel = r.index
            choose(r.index)
            return
        end
    end
end

-- Converte a posição do app para a escala do OSD (a tela pode ter escala do sistema, ex.: 150%)
local function set_mouse(x, y, cw, ch)
    local w, h = mp.get_osd_size()
    x, y, cw, ch = tonumber(x), tonumber(y), tonumber(cw), tonumber(ch)
    if not (x and y and cw and ch) or cw <= 0 or ch <= 0 or not w or w == 0 then return false end
    mouse.x, mouse.y, mouse.inside = x * w / cw, y * h / ch, true
    return true
end

local function hit(x, y)
    for i = #boxes, 1, -1 do
        local b = boxes[i]
        if x >= b.x1 and x <= b.x2 and y >= b.y1 and y <= b.y2 then return b end
    end
    return nil
end

local function on_click(x, y, cw, ch)
    if not set_mouse(x, y, cw, ch) then return end
    if menu then menu_click(mouse.x, mouse.y); poke(); return end
    local was_visible = visible()
    render()   -- caixas atualizadas para a posição atual
    local b = hit(mouse.x, mouse.y)
    if b then
        video_click = nil
        act(b.action, b.arg)
        poke()
        return
    end
    -- clique no vídeo: pausa/continua (no ao vivo, só mostra/esconde os controles)
    if info.kind ~= "live" then
        mp.commandv("cycle", "pause")
        video_click = { t = now(), toggled = true }
        poke()
    else
        video_click = { t = now(), toggled = false }
        if was_visible then last_activity = 0; render() else poke() end
    end
end

local function on_dbl(x, y, cw, ch)
    if not set_mouse(x, y, cw, ch) then return end
    if menu then menu_click(mouse.x, mouse.y); poke(); return end
    render()
    local b = hit(mouse.x, mouse.y)
    if b then
        -- duplo clique num botão: vale como mais um clique (ex.: +10 s duas vezes)
        if b.action == "rew" or b.action == "fwd" or b.action == "ch-up" or b.action == "ch-down" then act(b.action, b.arg) end
        poke()
        return
    end
    -- duplo clique no vídeo: tela cheia, desfazendo a pausa do primeiro clique
    if video_click and video_click.toggled and now() - video_click.t < 0.8 then mp.commandv("cycle", "pause") end
    video_click = nil
    mp.commandv("script-message", "sb-tela-cheia")
    poke()
end

mp.register_script_message("sb-mouse", function(x, y, cw, ch)
    if set_mouse(x, y, cw, ch) then poke() end
end)
mp.register_script_message("sb-leave", function()
    mouse.inside = false
    render()
end)
mp.register_script_message("sb-click", on_click)
mp.register_script_message("sb-dbl", on_dbl)
mp.register_script_message("sb-wheel", function(d)
    d = tonumber(d) or 0
    if menu then
        menu.scroll = menu.scroll + d * 3
        if menu_geom then
            menu.scroll = math.max(0, math.min(menu.scroll, math.max(0, #menu.items - menu_geom.visible_rows)))
        end
        poke()
    else
        mp.commandv("add", "volume", tostring(-d * 5))
        if d < 0 then mp.set_property_bool("mute", false) end
        show_volume()
        poke()
    end
end)

-- ---------- menus ----------

-- As teclas do menu são as mesmas dos controles (ver abaixo): cada tecla olha se há menu aberto.
-- (Duas ligações forçadas para a mesma tecla no mesmo script ficam em ordem indefinida no mpv.)
bind_menu_keys = function(_) end

mp.register_script_message("sb-menu", function(json)
    local m = utils.parse_json(json or "")
    if type(m) ~= "table" then return end
    m.items = m.items or {}
    local keep_scroll = menu and menu.id == m.id and menu.group == m.group and menu.scroll or nil
    m.sel = tonumber(m.sel) or 0
    if m.sel < 1 then
        m.sel = 1
        for i, it in ipairs(m.items) do if it.checked then m.sel = i; break end end
    end
    m.scroll = keep_scroll or math.max(0, m.sel - 4)
    local was_open = menu ~= nil
    menu = m
    if not was_open then
        bind_menu_keys(true)
        mp.commandv("script-message", "sb-menu-aberto", "1")
    end
    poke()
end)

mp.register_script_message("sb-close-menu", close_menu)

-- ---------- teclas (vêm do app como keypress) ----------

local function key(k, f) mp.add_forced_key_binding(k, "sb-" .. k, f, { repeatable = true }) end

key("BS", function() if menu then close_menu() else mp.commandv("script-message", "sb-voltar") end end)
key("q", function() mp.commandv("script-message", "sb-voltar") end)
key("f", function() mp.commandv("script-message", "sb-tela-cheia") end)
key("ENTER", function()
    if menu then choose(menu.sel); return end
    render()
    for _, b in ipairs(boxes) do
        if b.action == "skip" or b.action == "next-now" then act(b.action, b.arg); poke(); return end
    end
    poke()
end)
key("SPACE", function()
    if menu then choose(menu.sel); return end
    mp.commandv("cycle", "pause"); poke()
end)
key("LEFT", function()
    if menu then if menu.left then send_cmd(menu.left) end; return end
    if info.kind == "live" then poke(); return end
    act("rew"); poke()
end)
key("RIGHT", function()
    if menu then if menu.right then send_cmd(menu.right) end; return end
    if info.kind == "live" then poke(); return end
    act("fwd"); poke()
end)
key("UP", function()
    if menu then move_sel(-1); return end
    if info.kind == "live" then act("ch-up") else mp.commandv("seek", "60"); mp.osd_message("+1 min", 1) end
    poke()
end)
key("DOWN", function()
    if menu then move_sel(1); return end
    if info.kind == "live" then act("ch-down") else mp.commandv("seek", "-60"); mp.osd_message("-1 min", 1) end
    poke()
end)
key("PGUP", function()
    if menu then move_sel(-(menu_geom and menu_geom.visible_rows or 8)); return end
    if info.kind == "live" then act("ch-up") end; poke() end)
key("PGDWN", function()
    if menu then move_sel(menu_geom and menu_geom.visible_rows or 8); return end
    if info.kind == "live" then act("ch-down") end; poke() end)
key("HOME", function() if menu then move_sel(-#menu.items) end end)
key("END", function() if menu then move_sel(#menu.items) end end)
key("s", function() act("menu-legendas") end)
key("j", function() act("menu-legendas") end)
key("a", function() act("menu-audio") end)
key("#", function() act("menu-audio") end)
key("e", function() if info.episodes then act("menu-episodios") end end)
-- Sincronia da legenda: G adianta, H atrasa (0,25 s)
key("g", function() if info.kind ~= "live" then mp.commandv("script-message", "sb-escolha", "sync", "-250", "tecla") end end)
key("h", function() if info.kind ~= "live" then mp.commandv("script-message", "sb-escolha", "sync", "250", "tecla") end end)
key("c", function() if info.kind == "live" then act("menu-canais") end end)
-- TV ao vivo: número do canal pelo teclado (o app junta os dígitos e sintoniza)
for d = 0, 9 do
    key(tostring(d), function()
        if info.kind == "live" then mp.commandv("script-message", "sb-digito", tostring(d)) end
    end)
end
key("m", function() act("mute"); poke() end)
-- Volume pelo teclado: + / = aumenta, - diminui (5 em 5)
local function vol_key(d)
    return function()
        mp.commandv("add", "volume", tostring(d))
        if d > 0 then mp.set_property_bool("mute", false) end
        show_volume(); poke()
    end
end
key("+", vol_key(5)); key("=", vol_key(5)); key("KP_ADD", vol_key(5))
key("-", vol_key(-5)); key("KP_SUBTRACT", vol_key(-5))

-- ---------- informações vindas do app ----------

mp.register_script_message("sb-info", function(kind, title, subtitle, episodes)
    info.kind = kind or "vod"
    info.title = title or ""
    info.subtitle = subtitle or ""
    info.episodes = episodes == "1"
    poke()
end)

mp.register_script_message("sb-segmentos", function(json)
    local list = utils.parse_json(json or "[]")
    segments = type(list) == "table" and list or {}
    render()
end)

mp.register_script_message("sb-proximo", function(label)
    next_label = (label and label ~= "") and label or nil
    next_dismissed = false
    next_deadline = nil
    render()
end)

mp.register_event("start-file", function()
    segments = {}
    next_label = nil
    next_dismissed = false
    next_deadline = nil
end)

mp.add_periodic_timer(0.1, render)
mp.observe_property("pause", "bool", function() poke() end)
mp.observe_property("volume", "number", function() render() end)
mp.observe_property("mute", "bool", function() render() end)
mp.register_event("file-loaded", poke)
