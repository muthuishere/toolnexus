// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLlmsTxt from 'starlight-llms-txt';
import starlightSidebarTopics from 'starlight-sidebar-topics';
import { apiTopics } from './scripts/lib/sidebar.mjs';
import { readFileSync } from 'node:fs';

// The released version, for the header badge. js/package.json is the source of
// truth by proxy: release.yml's `preflight` fails the run if the six manifests
// disagree, so any one of them is the version.
const toolnexusVersion = JSON.parse(readFileSync('../js/package.json', 'utf8')).version;

// Project GitHub Pages: https://muthuishere.github.io/toolnexus
// https://astro.build/config
export default defineConfig({
	vite: { define: { __TOOLNEXUS_VERSION__: JSON.stringify(toolnexusVersion) } },
	site: 'https://muthuishere.github.io',
	base: '/toolnexus',
	// The old "One demo, five sources" page duplicated the quickstart; published
	// links to it must not 404.
	// The live-harness page moved out of Scenarios into its own section; published
	// links to the old slug must not 404.
	redirects: {
		'/demo': '/toolnexus/quickstart/',
		'/scenarios/harness-live': '/toolnexus/harness/live/',
	},
	integrations: [
		starlight({
			title: 'toolnexus',
			// Remove the right-hand "On this page" table of contents site-wide.
			tableOfContents: false,
			description:
				'Your LLM, with MCP tools and agent skills built in — in 3 lines, in 7 languages. Vendor-neutral, byte-identical across JavaScript, Python, Go, Java, C#, Elixir and Clojure.',
			plugins: [
				starlightLlmsTxt({
					projectName: 'toolnexus',
					description:
						'A small, vendor-neutral library that gives any LLM dynamic tool-calling: an MCP host, agent skills, native + HTTP + built-in tools, remote A2A agents, a human-in-the-loop suspension layer, and a unified client loop — byte-identical across JavaScript, Python, Go, Java, C#, Elixir and Clojure — all seven published to their registries.',
					details:
						'toolnexus unifies every tool source (MCP servers, agent skills, your own functions via defineTool, HTTP/REST endpoints, built-in shell/file tools, and remote A2A agents) behind one Tool interface, emits schema in OpenAI/Anthropic/Gemini formats, and ships a client with a built-in tool-calling loop (skills injection, parallel + chained calls, hooks, streaming, retries, conversation memory, observability). The same examples/ fixtures produce identical behavior in all seven ports, each held to the full tier by a shared conformance gate. The Clojure port runs one .cljc source tree on both the JVM and cljgo, published to Clojars.',
				}),
				// Splits the whole site into topics, each with its own dedicated sidebar (switchable
				// via the topic picker at the top of the nav): the main "Docs" topic below, plus one
				// topic per language for the API reference — so /api/go/ no longer shows all six
				// languages' trees nested (collapsed) alongside Go's own.
				starlightSidebarTopics(
					[
						{
							label: 'Docs',
							link: 'quickstart/',
							items: [
								{
									label: 'Start here',
									items: [
										{ label: 'Quickstart', slug: 'quickstart' },
									],
								},
								{
									label: 'Scenarios — full builds',
									items: [
										{ label: 'Which scenario?', slug: 'scenarios' },
										{ label: 'A coding agent', slug: 'scenarios/coding-agent' },
										{ label: 'A persona assistant', slug: 'scenarios/persona-assistant' },
										{ label: 'A research orchestrator', slug: 'scenarios/research-orchestrator' },
										{ label: 'A support agent', slug: 'scenarios/support-agent' },
										{ label: 'A self-improving agent', slug: 'scenarios/self-improving-agent' },
										{ label: 'A verify-before-commit gate', slug: 'scenarios/verify-gate' },
									],
								},
								{
									label: 'Harness & loop',
									items: [
										{ label: 'Harness & loop', slug: 'harness' },
										{ label: 'Completion gate & guardrails', slug: 'harness/completion-gate' },
										{ label: 'Proved on live models', slug: 'harness/live' },
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
										{ label: 'Enable / disable tools', slug: 'cookbook/enable-disable-tools' },
										{ label: 'Bring your own HTTP client', slug: 'cookbook/bring-your-own-http-client' },
										{ label: 'Local & in-process models', slug: 'cookbook/local-and-in-process-models' },
										{ label: 'Fail fast, or retry', slug: 'cookbook/fail-fast-or-retry' },
										{ label: 'Multi-turn memory', slug: 'cookbook/memory' },
										{ label: 'Sub-agents & teams', slug: 'cookbook/subagents' },
									],
								},
								{
									label: 'Benchmarks & comparison',
									items: [
										{ label: 'Comparison — Spring AI · LangGraph · ADK · Mastra', slug: 'comparison' },
										{ label: 'Performance benchmarks', slug: 'performance' },
										{ label: 'Resilience benchmark', slug: 'resilience' },
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
										{ label: 'Sub-agents & teams', slug: 'subagents' },
										{ label: 'Persona agents', slug: 'persona-agents' },
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
									items: [{ label: 'All languages — the surface', slug: 'api' }],
								},
								{
									label: 'Reference',
									items: [
										{ label: 'Install — all seven languages', slug: 'install' },
										{ label: 'Ecosystem & references', slug: 'references' },
									],
								},
							],
						},
						...apiTopics(),
					],
					// The homepage uses `template: splash` and never renders a sidebar.
					{ exclude: ['index'] },
				),
			],
			customCss: ['@fontsource-variable/inter', './src/styles/deemwar.css'],
			social: [
				{ icon: 'github', label: 'GitHub', href: 'https://github.com/muthuishere/toolnexus' },
			],
			components: {
				// Suppresses starlight-sidebar-topics' default topic picker (which would list all
				// six language topics at the top of every sidebar) — see Sidebar.astro.
				Sidebar: './src/components/Sidebar.astro',
				// Adds a language picker next to the theme select — see Header.astro.
				Header: './src/components/Header.astro',
			},
		}),
	],
});
