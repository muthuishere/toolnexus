// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

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
