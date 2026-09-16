import { ConfigService, KookitConfig } from "../../assets/lib/kookit-extra-browser.min";
import { chatStream } from "./common";

export type AiModelConfig = {
  endpoint: string;
  providerId: string;
  apiKey: string;
  modelId: string;
};

/**
 * 本地全功能模式：官方云端 AI 已移除，AI 能力统一走用户自配的 API key。
 * 这里按 翻译 → 词典 → 助手 的优先级取第一个可用模型配置，
 * 供整书翻译 / 书名解析等原官方云端 AI 功能复用。
 */
export const getFirstAiModelConfig = (): AiModelConfig | null => {
  const priorityKeys = [
    "aiTranslateModel",
    "aiDictModel",
    "aiAssistanceModel",
  ];
  for (const key of priorityKeys) {
    const modelKey = ConfigService.getReaderConfig(key);
    if (!modelKey) {
      continue;
    }
    const entry = ConfigService.getObjectConfig(modelKey, "aiModelConfig", null);
    const config: any = (entry && entry.config) || {};
    if (config.endpoint && config.modelId) {
      return {
        endpoint: String(config.endpoint).replace(/\/+$/, ""),
        providerId: config.providerId || "",
        apiKey: config.apiKey || "",
        modelId: config.modelId,
      };
    }
  }
  return null;
};

/** 非流式收集一次完整回答 */
const collectChatText = (
  prompt: string,
  config: AiModelConfig
): Promise<string> => {
  return new Promise((resolve, reject) => {
    let acc = "";
    let settled = false;
    const finish = (err?: any) => {
      if (settled) {
        return;
      }
      settled = true;
      if (err) {
        reject(err);
      } else {
        resolve(acc);
      }
    };
    chatStream(
      config.endpoint,
      config.providerId,
      config.apiKey,
      config.modelId,
      prompt,
      [],
      (result) => {
        if (result && result.done) {
          finish();
        } else if (result && result.text) {
          acc += result.text;
        }
      }
    )
      .then(() => finish())
      .catch((err) => finish(err));
  });
};

const langName = (code: string): string => {
  if (!code || code === "Automatic" || code.toLowerCase() === "auto") {
    return "auto-detect the source language";
  }
  const map: Record<string, string> = KookitConfig.ConvertLangMap || {};
  return map[code] || code;
};

/** 从 LLM 文本中提取 JSON 数组（容忍 code fence / 前后说明文字） */
const extractJsonArray = (text: string): string[] | null => {
  if (!text) {
    return null;
  }
  const cleaned = text.replace(/```json/gi, "```");
  const start = cleaned.indexOf("[");
  const end = cleaned.lastIndexOf("]");
  if (start !== -1 && end > start) {
    try {
      const parsed = JSON.parse(cleaned.slice(start, end + 1));
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item));
      }
    } catch (e) {
      // fall through to line-split fallback at the call site
    }
  }
  return null;
};

const CHUNK_SIZE = 8;

/**
 * 用自配 AI 批量翻译文本段（替代原官方云端 getBatchTrans）。
 * 返回值与输入一一对应；未配置模型或解析失败时返回 null，
 * 由调用方给出「请先配置 AI 服务」提示。
 */
export const aiTranslateTexts = async (
  texts: string[],
  from: string,
  to: string
): Promise<string[] | null> => {
  if (!texts || texts.length === 0) {
    return [];
  }
  const config = getFirstAiModelConfig();
  if (!config) {
    return null;
  }
  const results: string[] = new Array(texts.length).fill("");
  for (let i = 0; i < texts.length; i += CHUNK_SIZE) {
    const chunk = texts.slice(i, i + CHUNK_SIZE);
    const prompt = [
      `You are a professional translator. Translate each of the following ${chunk.length} text segments from ${langName(
        from
      )} to ${langName(to)}.`,
      "Preserve meaning, tone and line breaks inside each segment.",
      "Return ONLY a minified JSON array of strings, in the same order and with the same length as the input. No explanations, no markdown.",
      "Input JSON array: " + JSON.stringify(chunk),
    ].join("\n");
    try {
      const answer = await collectChatText(prompt, config);
      let translated = extractJsonArray(answer);
      if (!translated || translated.length !== chunk.length) {
        // 兜底：按行拆分
        const lines = answer
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter((line) => line.length > 0);
        if (lines.length === chunk.length) {
          translated = lines;
        }
      }
      if (!translated || translated.length !== chunk.length) {
        return null;
      }
      for (let j = 0; j < chunk.length; j++) {
        results[i + j] = translated[j];
      }
    } catch (error) {
      console.error("aiTranslateTexts failed:", error);
      return null;
    }
  }
  return results;
};

/**
 * 用自配 AI 解析书名中的书名/作者（替代原官方云端 analyzeBookTitle）。
 * 未配置模型或解析失败时返回 null，由调用方保留原书名。
 */
export const aiAnalyzeTitle = async (
  title: string
): Promise<{ name: string; author: string } | null> => {
  if (!title) {
    return null;
  }
  const config = getFirstAiModelConfig();
  if (!config) {
    return null;
  }
  const prompt = [
    `Parse the book file name "${title}" into its book name and author (the name field may keep series/volume info; author may be empty).`,
    "Answer in the language of the title itself.",
    "Return ONLY a minified JSON object like {\"name\":\"...\",\"author\":\"...\"}. No explanations, no markdown.",
  ].join("\n");
  try {
    const answer = await collectChatText(prompt, config);
    if (!answer) {
      return null;
    }
    const start = answer.indexOf("{");
    const end = answer.lastIndexOf("}");
    if (start === -1 || end <= start) {
      return null;
    }
    const parsed = JSON.parse(answer.slice(start, end + 1));
    if (parsed && typeof parsed.name === "string" && parsed.name.trim()) {
      return {
        name: parsed.name.trim(),
        author: typeof parsed.author === "string" ? parsed.author.trim() : "",
      };
    }
    return null;
  } catch (error) {
    console.error("aiAnalyzeTitle failed:", error);
    return null;
  }
};
