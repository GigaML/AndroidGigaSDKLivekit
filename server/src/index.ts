import 'dotenv/config';

import cors from 'cors';
import express from 'express';
import { z } from 'zod';

const optionalNonEmptyString = z.preprocess((value) => {
  if (typeof value !== 'string') {
    return value;
  }

  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}, z.string().min(1).optional());

const initializationValuesSchema = z.record(z.string(), z.unknown()).default({});
const initializationOptionSchema = z.object({
  description: optionalNonEmptyString,
  id: optionalNonEmptyString,
  label: z.string().trim().min(1),
  values: initializationValuesSchema,
});

const envSchema = z.object({
  PORT: z.string().default('8787'),
  GIGA_API_KEY: z.string().min(1),
  GIGA_AGENT_ID: optionalNonEmptyString,
  GIGA_AGENT_TEMPLATE_ID: optionalNonEmptyString,
  GIGA_INITIALIZATION_OPTIONS: optionalNonEmptyString,
  GIGA_CHAT_URL: z.url().default('https://agents.gigaml.com/v1/chat/rest'),
  GIGA_ROOM_URL: z.url(),
});

const env = envSchema.parse({
  PORT: process.env.PORT,
  GIGA_API_KEY: process.env.GIGA_API_KEY,
  GIGA_AGENT_ID: process.env.GIGA_AGENT_ID,
  GIGA_AGENT_TEMPLATE_ID: process.env.GIGA_AGENT_TEMPLATE_ID,
  GIGA_INITIALIZATION_OPTIONS: process.env.GIGA_INITIALIZATION_OPTIONS,
  GIGA_CHAT_URL: process.env.GIGA_CHAT_URL,
  GIGA_ROOM_URL: process.env.GIGA_ROOM_URL,
});
const initializationOptions = parseInitializationOptions(
  env.GIGA_INITIALIZATION_OPTIONS,
);

if (env.GIGA_AGENT_ID == null && env.GIGA_AGENT_TEMPLATE_ID == null) {
  throw new Error(
    'Set either GIGA_AGENT_ID or GIGA_AGENT_TEMPLATE_ID in server/.env.',
  );
}

const roomRequestSchema = z
  .object({
    agentId: z.string().min(1).optional(),
    agentTemplateId: z.string().min(1).optional(),
    customerE164: z.string().min(1).optional(),
    experimentId: z.string().min(1).optional(),
    initializationValues: initializationValuesSchema,
    voiceId: z.string().min(1).optional(),
  })
  .refine(
    (value) => !(value.agentId && value.agentTemplateId),
    'Provide either agentId or agentTemplateId, not both.',
  );

const chatStartRequestSchema = z
  .object({
    agentId: z.string().min(1).optional(),
    agentTemplateId: z.string().min(1).optional(),
    initializationValues: initializationValuesSchema,
    sendWelcomeMessage: z.boolean().default(true),
  })
  .refine(
    (value) => !(value.agentId && value.agentTemplateId),
    'Provide either agentId or agentTemplateId, not both.',
  );

const chatSendRequestSchema = z.object({
  messageId: optionalNonEmptyString,
  text: z.string().trim().min(1),
  ticketId: z.string().trim().min(1),
});

const chatEndRequestSchema = z.object({
  reason: z.string().trim().default('AndroidGigaSDKLivekit sample chat ended'),
  status: z.string().trim().default('resolved'),
  ticketId: z.string().trim().min(1),
});

type AgentSelectionInput = {
  agentId?: string;
  agentTemplateId?: string;
};

type RoomProxyRequest = z.infer<typeof roomRequestSchema>;

const app = express();
app.use(cors());
app.use(express.json({ limit: '1mb' }));

app.get('/api/health', (_request, response) => {
  response.json({
    chatUrl: env.GIGA_CHAT_URL,
    ok: true,
    roomUrl: env.GIGA_ROOM_URL,
  });
});

app.get('/api/config', (_request, response) => {
  response.json(buildConfigResponse());
});

app.get('/api/voice/config', (_request, response) => {
  response.json(buildConfigResponse());
});

app.post('/api/voice/create-room', async (request, response) => {
  const parsed = roomRequestSchema.safeParse(request.body);
  if (!parsed.success) {
    return response.status(400).json({
      error: parsed.error.issues[0]?.message ?? 'Invalid room request.',
    });
  }

  const selection = resolveAgentSelection(parsed.data);
  const requestId = createRequestId('voice');
  const isLegacyRoomUrl = env.GIGA_ROOM_URL.includes('/get_room_id');

  try {
    const data = isLegacyRoomUrl
      ? await requestLegacyRoom({
          customerE164: parsed.data.customerE164,
          experimentId: parsed.data.experimentId,
          initializationValues: parsed.data.initializationValues,
          requestId,
          selection,
          url: env.GIGA_ROOM_URL,
          voiceId: parsed.data.voiceId,
        })
      : await requestGigaJson({
          body: buildRoomRequest(parsed.data),
          fallbackMessage: 'Room creation failed.',
          method: 'POST',
          requestId,
          url: env.GIGA_ROOM_URL,
        });

    return response.json(normalizeRoomResponse(data, selection));
  } catch (error) {
    return handleProxyError(response, error, 'Room creation failed.');
  }
});

app.post('/api/chat/start', async (request, response) => {
  const parsed = chatStartRequestSchema.safeParse(request.body);
  if (!parsed.success) {
    return response.status(400).json({
      error: parsed.error.issues[0]?.message ?? 'Invalid chat start request.',
    });
  }

  const selection = resolveAgentSelection(parsed.data);
  const ticketId = createTicketId();

  try {
    const data = await requestGigaJson({
      body: {
        agent_id: selection.agentId,
        agent_template_id: selection.agentTemplateId,
        chat_history: [],
        initialization_values: parsed.data.initializationValues,
        send_welcome_message: parsed.data.sendWelcomeMessage,
        ticket_id: ticketId,
      },
      fallbackMessage: 'Unable to start the chat session.',
      method: 'POST',
      url: buildChatUrl('/initiate-session'),
    });

    return response.json({
      agentId: selection.agentId ?? null,
      agentTemplateId: selection.agentTemplateId ?? null,
      ticketId,
      welcomeMessage: readString(data, 'message'),
    });
  } catch (error) {
    return handleProxyError(
      response,
      error,
      'Unable to start the chat session.',
    );
  }
});

app.post('/api/chat/send', async (request, response) => {
  const parsed = chatSendRequestSchema.safeParse(request.body);
  if (!parsed.success) {
    return response.status(400).json({
      error: parsed.error.issues[0]?.message ?? 'Invalid chat send request.',
    });
  }

  try {
    const data = await requestGigaJson({
      body: {
        message: {
          content: [
            {
              text: parsed.data.text,
              type: 'text',
            },
          ],
          message_id: parsed.data.messageId ?? createMessageId(),
          role: 'user',
        },
        ticket_id: parsed.data.ticketId,
      },
      fallbackMessage: 'Unable to send the chat message.',
      method: 'POST',
      url: buildChatUrl('/send-message'),
    });

    return response.json({
      message: normalizeChatMessage(data.message),
      responseType:
        readString(data, 'response_type') ??
        readString(data, 'responseType') ??
        'message',
    });
  } catch (error) {
    return handleProxyError(
      response,
      error,
      'Unable to send the chat message.',
    );
  }
});

app.post('/api/chat/end', async (request, response) => {
  const parsed = chatEndRequestSchema.safeParse(request.body);
  if (!parsed.success) {
    return response.status(400).json({
      error: parsed.error.issues[0]?.message ?? 'Invalid chat end request.',
    });
  }

  try {
    const data = await requestGigaJson({
      body: {
        reason: parsed.data.reason,
        status: parsed.data.status,
        ticket_id: parsed.data.ticketId,
      },
      fallbackMessage: 'Unable to end the chat session.',
      method: 'PUT',
      url: buildChatUrl('/close-session'),
    });

    return response.json({
      message: readString(data, 'message'),
      success: readBoolean(data, 'success') ?? true,
    });
  } catch (error) {
    return handleProxyError(response, error, 'Unable to end the chat session.');
  }
});

app.listen(Number(env.PORT), () => {
  console.log(
    `AndroidGigaSDKLivekit example server listening on http://127.0.0.1:${env.PORT}`,
  );
});

function buildConfigResponse() {
  return {
    chatEndpoint: '/api/chat/start',
    defaultAgentId: env.GIGA_AGENT_ID ?? null,
    defaultAgentTemplateId: env.GIGA_AGENT_TEMPLATE_ID ?? null,
    initializationOptions,
    roomEndpoint: '/api/voice/create-room',
    supports: {
      chat: true,
      voice: true,
    },
  };
}

function parseInitializationOptions(raw?: string) {
  if (raw == null) {
    return [];
  }

  try {
    return z
      .array(initializationOptionSchema)
      .parse(JSON.parse(raw))
      .map((option, index) => ({
        description: option.description,
        id: option.id ?? `preset-${index + 1}`,
        label: option.label,
        values: option.values,
      }));
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown parse error.';
    throw new Error(
      `GIGA_INITIALIZATION_OPTIONS must be a JSON array of preset objects. ${message}`,
    );
  }
}

function buildRoomRequest(input: RoomProxyRequest): Record<string, unknown> {
  const selection = resolveAgentSelection(input);

  return {
    agent_id: selection.agentId,
    agent_template_id: selection.agentTemplateId,
    customer_e164: input.customerE164,
    experiment_id: input.experimentId,
    initialization_values: input.initializationValues,
    version_target: 'auto',
    voice_id: input.voiceId,
  };
}

function resolveAgentSelection(input: AgentSelectionInput) {
  const agentId = input.agentId ?? env.GIGA_AGENT_ID;

  return {
    agentId,
    agentTemplateId:
      input.agentTemplateId ??
      (agentId == null ? env.GIGA_AGENT_TEMPLATE_ID : undefined),
  };
}

function buildChatUrl(path: string) {
  return `${env.GIGA_CHAT_URL.replace(/\/$/, '')}${path}`;
}

async function requestGigaJson(input: {
  body?: Record<string, unknown>;
  fallbackMessage: string;
  method: 'GET' | 'POST' | 'PUT';
  requestId?: string;
  url: string;
}) {
  const gigaResponse = await fetch(input.url, {
    body: input.body == null ? undefined : JSON.stringify(input.body),
    headers: {
      Authorization: `Bearer ${env.GIGA_API_KEY}`,
      ...(input.body == null ? {} : { 'Content-Type': 'application/json' }),
    },
    method: input.method,
  });

  const data = (await gigaResponse.json().catch(() => null)) as Record<
    string,
    unknown
  > | null;

  if (!gigaResponse.ok) {
    throw new ProxyError(
      gigaResponse.status,
      normalizeGigaError(readErrorMessage(data)) ?? input.fallbackMessage,
    );
  }

  return data ?? {};
}

async function requestLegacyRoom(input: {
  customerE164?: string;
  experimentId?: string;
  initializationValues: Record<string, unknown>;
  requestId?: string;
  selection: ReturnType<typeof resolveAgentSelection>;
  url: string;
  voiceId?: string;
}) {
  const url = new URL(input.url);

  if (input.selection.agentId != null) {
    url.searchParams.set('agent_id', input.selection.agentId);
  }

  if (input.selection.agentTemplateId != null) {
    url.searchParams.set('agent_template_id', input.selection.agentTemplateId);
  }

  if (input.customerE164 != null) {
    url.searchParams.set('customer_e164', input.customerE164);
  }

  if (input.experimentId != null) {
    url.searchParams.set('experiment_id', input.experimentId);
  }

  if (input.voiceId != null) {
    url.searchParams.set('voice_id', input.voiceId);
  }

  if (Object.keys(input.initializationValues).length > 0) {
    url.searchParams.set(
      'initialization_values',
      JSON.stringify(input.initializationValues),
    );
  }

  return requestGigaJson({
    fallbackMessage: 'Room creation failed.',
    method: 'GET',
    requestId: input.requestId,
    url: url.toString(),
  });
}

function normalizeRoomResponse(
  data: Record<string, unknown>,
  selection: ReturnType<typeof resolveAgentSelection>,
) {
  return {
    agentId:
      readString(data, 'resolved_agent_id') ??
      readString(data, 'resolvedAgentId') ??
      readString(data, 'agent_id') ??
      readString(data, 'agentId') ??
      selection.agentId ??
      null,
    agentTemplateId:
      readString(data, 'agent_template_id') ??
      readString(data, 'agentTemplateId') ??
      selection.agentTemplateId ??
      null,
    initializationSchema:
      readRecord(data, 'initialization_schema') ??
      readRecord(data, 'initializationSchema'),
    participantName: readRequiredString(
      data,
      'participant_name',
      'participantName',
    ),
    participantToken: readRequiredString(
      data,
      'participant_token',
      'participantToken',
    ),
    roomId: readRequiredString(data, 'room_id', 'roomId'),
    roomName: readRequiredString(data, 'room_name', 'roomName'),
    serverUrl: readRequiredString(data, 'server_url', 'serverUrl'),
  };
}

function normalizeChatMessage(message: unknown) {
  if (typeof message === 'string') {
    return {
      messageId: null,
      role: 'assistant',
      text: message,
      imageUrls: extractImageUrlsFromText(message),
    };
  }

  if (!isRecord(message)) {
    return {
      messageId: null,
      role: 'assistant',
      text: '',
      imageUrls: [],
    };
  }

  return {
    messageId:
      readString(message, 'message_id') ?? readString(message, 'messageId'),
    role: typeof message.role === 'string' ? message.role : 'assistant',
    text: extractChatText(message.content),
    imageUrls: extractImageUrls(message.content),
  };
}

function extractChatText(content: unknown): string {
  if (typeof content === 'string') {
    return stripImageMarkup(content);
  }

  if (!Array.isArray(content)) {
    return '';
  }

  return content
    .map((part) => {
      if (!isRecord(part)) {
        return '';
      }

      if (typeof part.text === 'string') {
        return stripImageMarkup(part.text);
      }

      if (typeof part.content === 'string') {
        return stripImageMarkup(part.content);
      }

      return '';
    })
    .join('\n')
    .trim();
}

function extractImageUrls(content: unknown): string[] {
  if (typeof content === 'string') {
    return extractImageUrlsFromText(content);
  }

  if (!Array.isArray(content)) {
    return [];
  }

  return Array.from(
    new Set(
      content.flatMap((part) => {
        if (!isRecord(part)) {
          return [];
        }

        const directCandidates = [
          readString(part, 'url'),
          readString(part, 'image_url'),
          readString(part, 'imageUrl'),
          readString(part, 'source_url'),
          readString(part, 'sourceUrl'),
          readString(part, 'text'),
          readString(part, 'content'),
        ].filter((value): value is string => value != null);

        const nestedCandidates = [
          readNestedString(part.image_url, 'url'),
          readNestedString(part.imageUrl, 'url'),
          readNestedString(part.source, 'url'),
          readNestedString(part.source_url, 'url'),
          readNestedString(part.sourceUrl, 'url'),
          readNestedString(part.image_url, 'uri'),
          readNestedString(part.imageUrl, 'uri'),
          readNestedString(part.source, 'uri'),
          readNestedString(part.source_url, 'uri'),
          readNestedString(part.sourceUrl, 'uri'),
        ].filter((value): value is string => value != null);

        return [...directCandidates, ...nestedCandidates].flatMap(
          extractImageUrlsFromText,
        );
      }),
    ),
  );
}

function extractImageUrlsFromText(text: string): string[] {
  return Array.from(text.matchAll(IMAGE_URL_REGEX), (match) => match[0]);
}

function stripImageMarkup(text: string): string {
  return text
    .replace(IMAGE_TAG_REGEX, '')
    .replace(MARKDOWN_IMAGE_REGEX, '')
    .replace(EMPTY_MARKDOWN_IMAGE_REGEX, '')
    .replace(IMAGE_URL_REGEX, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function createTicketId() {
  return `android_${Date.now()}_${crypto.randomUUID()}`;
}

function createMessageId() {
  return `msg_${crypto.randomUUID()}`;
}

function createRequestId(prefix: string) {
  return `${prefix}_${Date.now()}_${crypto.randomUUID()}`;
}

function readErrorMessage(data: Record<string, unknown> | null): string | null {
  if (data == null) {
    return null;
  }

  const candidate = data.error ?? data.message ?? data.detail;
  return typeof candidate === 'string' && candidate.length > 0
    ? candidate
    : null;
}

function normalizeGigaError(message: string | null): string | null {
  if (message == null) {
    return null;
  }

  if (message.includes("'NoneType' object has no attribute 'id'")) {
    return 'The configured agent template does not have a live agent version. Publish the template or set GIGA_AGENT_ID to a runnable agent version.';
  }

  return message;
}

function handleProxyError(
  response: express.Response,
  error: unknown,
  fallbackMessage: string,
) {
  console.error('[android-server:error]', summarizeError(error));

  if (error instanceof ProxyError) {
    return response.status(error.status).json({
      error: error.message,
    });
  }

  return response.status(502).json({
    error: error instanceof Error ? error.message : fallbackMessage,
  });
}

function readRequiredString(data: Record<string, unknown>, ...keys: string[]) {
  for (const key of keys) {
    const value = data[key];
    if (typeof value === 'string' && value.length > 0) {
      return value;
    }
  }

  throw new Error(`Giga response is missing "${keys.join('" or "')}".`);
}

function readString(data: Record<string, unknown>, key: string) {
  const value = data[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function readBoolean(data: Record<string, unknown>, key: string) {
  const value = data[key];
  return typeof value === 'boolean' ? value : null;
}

function readRecord(data: Record<string, unknown>, key: string) {
  const value = data[key];
  return isRecord(value) ? value : null;
}

function readNestedString(value: unknown, key: string) {
  if (!isRecord(value)) {
    return null;
  }

  const nestedValue = value[key];
  return typeof nestedValue === 'string' && nestedValue.length > 0
    ? nestedValue
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function summarizeError(error: unknown) {
  if (error instanceof Error) {
    return {
      message: error.message,
      name: error.name,
      stack: error.stack ?? null,
      status: error instanceof ProxyError ? error.status : null,
    };
  }

  return {
    message: String(error),
    name: 'UnknownError',
    stack: null,
    status: null,
  };
}

class ProxyError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ProxyError';
    this.status = status;
  }
}

const IMAGE_URL_REGEX = /https?:\/\/\S+\.(?:png|jpe?g|gif|webp)(?:\?\S*)?/gi;
const IMAGE_TAG_REGEX = /\[IMG\]<\s*https?:\/\/[^>]+>\[\/IMG\]/gi;
const MARKDOWN_IMAGE_REGEX = /!\[[^\]]*]\(\s*https?:\/\/[^)]+\)/gi;
const EMPTY_MARKDOWN_IMAGE_REGEX = /!\[[^\]]*]\(\s*\)/gi;
