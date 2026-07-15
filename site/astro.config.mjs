// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';

// Project GitHub Pages: https://muthuishere.github.io/toolnexus
// https://astro.build/config
export default defineConfig({
	site: 'https://muthuishere.github.io',
	base: '/toolnexus',
	integrations: [
		starlight({
			title: 'toolnexus',
			// Remove the right-hand "On this page" table of contents site-wide.
			tableOfContents: false,
			description:
				'Your LLM, with MCP tools and agent skills built in — in 3 lines, in 6 languages. Vendor-neutral, byte-identical across JavaScript, Python, Go, Java, C# and Elixir.',
			plugins: [
				starlightLlmsTxt({
					projectName: 'toolnexus',
					description:
						'A small, vendor-neutral library that gives any LLM dynamic tool-calling: an MCP host, agent skills, native + HTTP + built-in tools, remote A2A agents, a human-in-the-loop suspension layer, and a unified client loop — byte-identical across JavaScript, Python, Go, Java, C# and Elixir.',
					details:
						'toolnexus unifies every tool source (MCP servers, agent skills, your own functions via defineTool, HTTP/REST endpoints, built-in shell/file tools, and remote A2A agents) behind one Tool interface, emits schema in OpenAI/Anthropic/Gemini formats, and ships a client with a built-in tool-calling loop (skills injection, parallel + chained calls, hooks, streaming, retries, conversation memory, observability). The same examples/ fixtures produce identical behavior in all six ports.',
				}),
			],
			customCss: ['@fontsource-variable/inter', './src/styles/deemwar.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/muthuishere/toolnexus' },
			],
			sidebar: [
				{
					label: 'Start here',
					items: [
						{ label: 'Quickstart', slug: 'quickstart' },
						{ label: 'One demo, five sources', slug: 'demo' },
					],
				},
				{
					label: 'Cookbook',
					items: [
						{ label: 'Zero to agent', slug: 'cookbook/zero-to-agent' },
						{ label: 'MCP servers', slug: 'cookbook/mcp-servers' },
						{ label: 'Agent skills', slug: 'cookbook/agent-skills' },
						{ label: 'Native tools', slug: 'cookbook/native-tool' },
						{ label: 'HTTP / REST tools', slug: 'cookbook/http-tool' },
						{ label: 'Bring your own HTTP client', slug: 'cookbook/bring-your-own-http-client' },
						{ label: 'Multi-turn memory', slug: 'cookbook/memory' },
					],
				},
				{
					label: 'Benchmarks & comparison',
					items: [
						{ label: 'Comparison — Spring AI · LangGraph · ADK', slug: 'comparison' },
						{ label: 'Performance benchmarks', slug: 'performance' },
					],
				},
				{
					label: 'Concepts',
					items: [{ label: 'A tool is a tool is a tool', slug: 'concepts' }],
				},
				{
					label: 'Tool sources',
					items: [
						{ label: 'MCP servers', slug: 'mcp' },
						{ label: 'Agent skills', slug: 'skills' },
						{ label: 'Native tools — your functions', slug: 'native' },
						{ label: 'HTTP / REST tools', slug: 'http' },
						{ label: 'Built-in tools', slug: 'builtins' },
						{ label: 'A2A — remote agents', slug: 'a2a' },
						{ label: 'Suspension & the human loop', slug: 'suspension' },
					],
				},
				{
					label: 'The client & loop',
					items: [
						{ label: 'Memory & conversations', slug: 'memory' },
						{ label: 'Streaming & hooks', slug: 'streaming' },
						{ label: 'Observability & metrics', slug: 'observability' },
					],
				},
				{
					label: 'API Reference',
					items: [
						{ label: 'All languages — the surface', slug: 'api' },
						{ label: 'JavaScript', slug: 'api/javascript' },
						{ label: 'Python', slug: 'api/python' },
						{ label: 'Go', slug: 'api/go' },
						{ label: 'Java', slug: 'api/java' },
						{ label: 'C#', slug: 'api/csharp' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'Install — all six languages', slug: 'install' },
						{ label: 'Ecosystem & references', slug: 'references' },
					],
				},
			],
		}),
	],
});
