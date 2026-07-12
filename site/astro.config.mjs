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
			description:
				'Your LLM, with MCP tools and agent skills built in — in 3 lines, in 5 languages. Vendor-neutral, byte-identical across JavaScript, Python, Go, Java and C#.',
			plugins: [
				starlightLlmsTxt({
					projectName: 'toolnexus',
					description:
						'A small, vendor-neutral library that gives any LLM dynamic tool-calling: an MCP host, agent skills, native + HTTP + built-in tools, remote A2A agents, a human-in-the-loop suspension layer, and a unified client loop — byte-identical across JavaScript, Python, Go, Java and C#.',
					details:
						'toolnexus unifies every tool source (MCP servers, agent skills, your own functions via defineTool, HTTP/REST endpoints, built-in shell/file tools, and remote A2A agents) behind one Tool interface, emits schema in OpenAI/Anthropic/Gemini formats, and ships a client with a built-in tool-calling loop (skills injection, parallel + chained calls, hooks, streaming, retries, conversation memory, observability). The same examples/ fixtures produce identical behavior in all five ports.',
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
					label: 'Concepts',
					items: [{ label: 'A tool is a tool is a tool', slug: 'concepts' }],
				},
				{
					label: 'Tool sources',
					items: [
						{ label: 'MCP servers', slug: 'mcp' },
						{ label: 'Agent skills', slug: 'skills' },
						{ label: 'Suspension & the human loop', slug: 'suspension' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ label: 'Install — all five languages', slug: 'install' },
						{ label: 'Ecosystem & references', slug: 'references' },
					],
				},
			],
		}),
	],
});
