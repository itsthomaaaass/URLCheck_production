import type { ReactNode } from "react";

/**
 * Renders the assistant's answers, which arrive as Markdown.
 *
 * Without this, a bubble shows the raw source: literal "**", "- " and fence
 * markers, and links that are not links. This turns the subset the answers
 * actually use into React elements, never HTML, so an answer can never inject
 * markup into the page.
 *
 * Only assistant bubbles go through here. A user's own message stays exactly as
 * typed, where a line break is a line break.
 */

type Block =
  | { kind: "paragraph"; text: string }
  | { kind: "heading"; level: number; text: string }
  | { kind: "code"; code: string }
  | { kind: "list"; ordered: boolean; start: number; items: Block[][] }
  | { kind: "quote"; blocks: Block[] }
  | { kind: "rule" }
  | { kind: "table"; header: string[]; rows: string[][] };

const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const FENCE = /^\s*(?:```|~~~)\s*[A-Za-z0-9+#._-]*\s*$/;
const RULE = /^\s*([-*_])(?:\s*\1){2,}\s*$/;
const BULLET = /^(\s*)[-*+]\s+(.*)$/;
const ORDERED = /^(\s*)(\d{1,9})[.)]\s+(.*)$/;
const QUOTE = /^\s*>\s?(.*)$/;

/** The assistant's answer as elements, ready to sit inside its bubble. */
export function renderMarkdown(source: string): ReactNode {
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  return <>{renderBlocks(parseBlocks(lines), "md")}</>;
}

function parseBlocks(lines: string[]): Block[] {
  const blocks: Block[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (line.trim() === "") {
      index += 1;
      continue;
    }

    if (FENCE.test(line)) {
      index += 1;
      const code: string[] = [];
      while (index < lines.length && !FENCE.test(lines[index])) {
        code.push(lines[index]);
        index += 1;
      }
      // Skip the closing fence, if the answer left one open.
      index += 1;
      blocks.push({ kind: "code", code: code.join("\n") });
      continue;
    }

    if (RULE.test(line)) {
      blocks.push({ kind: "rule" });
      index += 1;
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      blocks.push({ kind: "heading", level: heading[1].length, text: heading[2] });
      index += 1;
      continue;
    }

    if (QUOTE.test(line)) {
      const quoted: string[] = [];
      while (index < lines.length) {
        const match = QUOTE.exec(lines[index]);
        if (match) {
          quoted.push(match[1]);
          index += 1;
        } else if (lines[index].trim() !== "" && !isBlockStart(lines[index])) {
          // A paragraph continued without the ">", which Markdown allows.
          quoted.push(lines[index].trim());
          index += 1;
        } else {
          break;
        }
      }
      blocks.push({ kind: "quote", blocks: parseBlocks(quoted) });
      continue;
    }

    if (isTableStart(lines, index)) {
      const header = splitRow(lines[index]);
      index += 2; // the header and its separator
      const rows: string[][] = [];
      while (index < lines.length && lines[index].includes("|") && lines[index].trim() !== "") {
        rows.push(splitRow(lines[index]));
        index += 1;
      }
      blocks.push({ kind: "table", header, rows });
      continue;
    }

    if (BULLET.test(line) || ORDERED.test(line)) {
      const [list, next] = parseList(lines, index);
      blocks.push(list);
      index = next;
      continue;
    }

    const paragraph: string[] = [];
    while (index < lines.length && !isBlockStart(lines[index])) {
      paragraph.push(lines[index].trim());
      index += 1;
    }
    blocks.push({ kind: "paragraph", text: paragraph.join("\n") });
  }

  return blocks;
}

function parseList(lines: string[], start: number): [Block, number] {
  const first = listMarker(lines[start]);
  if (first === null) {
    return [{ kind: "paragraph", text: lines[start].trim() }, start + 1];
  }

  const items: Block[][] = [];
  let index = start;

  while (index < lines.length) {
    const marker = listMarker(lines[index]);
    // A switch between bullet and number, or a shallower line, ends the list.
    if (marker === null || marker.indent !== first.indent || marker.ordered !== first.ordered) {
      break;
    }

    const itemLines: string[] = [marker.text];
    // A blank line ends the item: only indented content follows it.
    let afterBlank = false;
    index += 1;

    while (index < lines.length) {
      const line = lines[index];
      if (line.trim() === "") {
        itemLines.push("");
        afterBlank = true;
        index += 1;
        continue;
      }

      const nested = listMarker(line);
      if (indentOf(line) <= first.indent) {
        if (nested === null && !isBlockStart(line) && !afterBlank) {
          // A continuation of the item's own paragraph, unindented.
          itemLines.push(line.trim());
          index += 1;
          continue;
        }
        break;
      }

      // Indented content belongs to the item; nested lists are parsed from it.
      itemLines.push(stripIndent(line, first.indent + 2));
      afterBlank = false;
      index += 1;
    }

    while (itemLines.length > 0 && itemLines[itemLines.length - 1].trim() === "") {
      itemLines.pop();
    }
    items.push(parseBlocks(itemLines));
  }

  return [{ kind: "list", ordered: first.ordered, start: first.number, items }, index];
}

interface Marker {
  indent: number;
  ordered: boolean;
  number: number;
  text: string;
}

function listMarker(line: string): Marker | null {
  const ordered = ORDERED.exec(line);
  if (ordered) {
    return {
      indent: indentOf(ordered[1]),
      ordered: true,
      number: Number(ordered[2]),
      text: ordered[3]
    };
  }
  const bullet = BULLET.exec(line);
  if (bullet) {
    return { indent: indentOf(bullet[1]), ordered: false, number: 1, text: bullet[2] };
  }
  return null;
}

function isBlockStart(line: string): boolean {
  return (
    line.trim() === "" ||
    HEADING.test(line) ||
    FENCE.test(line) ||
    RULE.test(line) ||
    QUOTE.test(line) ||
    BULLET.test(line) ||
    ORDERED.test(line)
  );
}

/** How far a line is indented, with a tab counting as two spaces. */
function indentOf(line: string): number {
  let indent = 0;
  for (const char of line) {
    if (char === " ") {
      indent += 1;
    } else if (char === "\t") {
      indent += 2;
    } else {
      break;
    }
  }
  return indent;
}

function stripIndent(line: string, amount: number): string {
  let index = 0;
  let removed = 0;
  while (index < line.length && removed < amount) {
    const char = line[index];
    if (char !== " " && char !== "\t") {
      break;
    }
    removed += char === "\t" ? 2 : 1;
    index += 1;
  }
  return line.slice(index);
}

/** A GitHub-style pipe table: a row of cells underlined by - and : markers. */
function isTableStart(lines: string[], index: number): boolean {
  if (index + 1 >= lines.length || !lines[index].includes("|")) {
    return false;
  }
  if (!lines[index + 1].includes("|")) {
    return false;
  }
  const header = splitRow(lines[index]);
  const separator = splitRow(lines[index + 1]);
  if (header.length !== separator.length || separator.length === 0) {
    return false;
  }
  return separator.every((cell) => /^:?-+:?$/.test(cell));
}

function splitRow(line: string): string[] {
  let text = line.trim();
  if (text.startsWith("|")) {
    text = text.slice(1);
  }
  if (text.endsWith("|")) {
    text = text.slice(0, -1);
  }
  return text.split("|").map((cell) => cell.trim());
}

const HEADING_TAGS = ["h1", "h2", "h3", "h4", "h5", "h6"] as const;

/** Every link this renderer will make clickable. */
const SAFE_HREF = /^(?:https?:\/\/|mailto:|\/(?!\/))/i;

function renderBlocks(blocks: Block[], keyPrefix: string): ReactNode[] {
  return blocks.map((block, index) => renderBlock(block, `${keyPrefix}-${index}`));
}

function renderBlock(block: Block, key: string): ReactNode {
  switch (block.kind) {
    case "paragraph":
      return <p key={key}>{renderLines(block.text, key)}</p>;

    case "heading": {
      const Tag = HEADING_TAGS[Math.min(block.level, HEADING_TAGS.length) - 1];
      return <Tag key={key}>{renderInline(block.text, key)}</Tag>;
    }

    case "code":
      return (
        <pre key={key} className="md-code">
          <code>{block.code}</code>
        </pre>
      );

    case "quote":
      return <blockquote key={key}>{renderBlocks(block.blocks, key)}</blockquote>;

    case "rule":
      return <hr key={key} />;

    case "table":
      return (
        <div key={key} className="md-table">
          <table>
            <thead>
              <tr>
                {block.header.map((cell, column) => (
                  <th key={column}>{renderInline(cell, `${key}-h${column}`)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  {row.map((cell, column) => (
                    <td key={column}>{renderInline(cell, `${key}-${rowIndex}-${column}`)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );

    case "list":
      return block.ordered ? (
        <ol key={key} start={block.start === 1 ? undefined : block.start}>
          {block.items.map((item, index) => (
            <li key={index}>{renderItem(item, `${key}-${index}`)}</li>
          ))}
        </ol>
      ) : (
        <ul key={key}>
          {block.items.map((item, index) => (
            <li key={index}>{renderItem(item, `${key}-${index}`)}</li>
          ))}
        </ul>
      );
  }
}

/** List items read better tight: a lone paragraph is not wrapped in a <p>. */
function renderItem(blocks: Block[], keyPrefix: string): ReactNode {
  if (blocks.length === 1 && blocks[0].kind === "paragraph") {
    return renderLines(blocks[0].text, keyPrefix);
  }
  return renderBlocks(blocks, keyPrefix);
}

/**
 * A paragraph keeps the answer's own line breaks, the way a chat shows text.
 */
function renderLines(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  text.split("\n").forEach((line, index) => {
    if (index > 0) {
      nodes.push(<br key={`${keyPrefix}-br${index}`} />);
    }
    nodes.push(...renderInline(line, `${keyPrefix}-l${index}`));
  });
  return nodes;
}

function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const pattern =
    /(`+)([\s\S]*?)\1|\*\*([\s\S]+?)\*\*|__([\s\S]+?)__|~~([\s\S]+?)~~|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])|(?<![\w_])_([^_\s][^_]*?)_(?![\w_])|\[([^\]]*)\]\((\S+?)(?:\s+"[^"]*")?\)|<([A-Za-z][A-Za-z0-9+.-]*:[^<>\s]+)>|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?"'’”])|\\([\\`*_{}\[\]()#+\-.!~>])/g;
  const nodes: ReactNode[] = [];
  let last = 0;
  let key = 0;

  for (const match of text.matchAll(pattern)) {
    if (match.index > last) {
      nodes.push(text.slice(last, match.index));
    }
    last = match.index + match[0].length;

    const nodeKey = `${keyPrefix}-i${key}`;
    key += 1;
    const [, , code, strong, strongAlt, strike, emphasis, emphasisAlt, label, href, autolink, url, escaped] =
      match;

    if (code !== undefined) {
      nodes.push(<code key={nodeKey}>{code}</code>);
    } else if (strong !== undefined) {
      nodes.push(<strong key={nodeKey}>{renderInline(strong, nodeKey)}</strong>);
    } else if (strongAlt !== undefined) {
      nodes.push(<strong key={nodeKey}>{renderInline(strongAlt, nodeKey)}</strong>);
    } else if (strike !== undefined) {
      nodes.push(<del key={nodeKey}>{renderInline(strike, nodeKey)}</del>);
    } else if (emphasis !== undefined) {
      nodes.push(<em key={nodeKey}>{renderInline(emphasis, nodeKey)}</em>);
    } else if (emphasisAlt !== undefined) {
      nodes.push(<em key={nodeKey}>{renderInline(emphasisAlt, nodeKey)}</em>);
    } else if (label !== undefined) {
      nodes.push(renderLink(href, label, nodeKey, true));
    } else if (autolink !== undefined) {
      nodes.push(renderLink(autolink, autolink, nodeKey, false));
    } else if (url !== undefined) {
      nodes.push(renderLink(url, url, nodeKey, false));
    } else if (escaped !== undefined) {
      nodes.push(escaped);
    }
  }

  if (last < text.length) {
    nodes.push(text.slice(last));
  }
  return nodes;
}

/**
 * A link, or — when the target is not plainly an address — just its text, so a
 * hostile "javascript:" target can never become a click.
 *
 * A bare address is its own label and is not parsed again: "https://…" inside a
 * link's text would otherwise find itself and recurse forever.
 */
function renderLink(href: string, label: string, key: string, parseLabel: boolean): ReactNode {
  if (!SAFE_HREF.test(href)) {
    return label === href ? href : `${label} (${href})`;
  }
  const text = label.toLowerCase().startsWith("mailto:") ? label.slice(7) : label;
  return (
    <a key={key} href={href} target="_blank" rel="noopener noreferrer">
      {parseLabel ? renderInline(text, key) : text}
    </a>
  );
}
