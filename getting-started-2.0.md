# Getting Started with Helix

This guide helps you get started with Helix with very little effort, then progressively discover more features as you need them.

## Publish your content

> You'll need:
> - a [GitHub](https://github.com/join) account
> - basic [Markdown](https://guides.github.com/features/mastering-markdown/) knowledge

The easiest way to publish content is to have [_Helix Pages_](https://www.project-helix.page/) render and deliver it for you. All you need is a GitHub repository with a Markdown file called `index.md`. And for your convenience, we have prepared one for you:

1. Go to [Helix Example 1](https://github.com/adobe/helix-example-1), click _Fork_ and confirm your GitHub organization.
2. Open the `index.md` file (`https://github.com/<my_org>/helix-example-1/edit/master/index.md`), add some great Markdown content, then click _Commit changes_.
3. Go to `https://helix-example-1--<my_org>.project-helix.page/` - voilà, you have just published your first page using _Helix Pages_. Congratulations! :-)

> Alternatively, you could also [create a public GitHub repository](https://github.com/new/) from scratch.

> Note: If your repository name contains a dash, add double dashes between the repository and organization name in the Helix Pages URL.

## Customize header and footer

_Helix Pages_ can render a header and footer based on Markdown content you put in your GitHub repository:

1. Go to [Helix Example 2](https://github.com/adobe/helix-example-2), click _Fork_ and confirm your GitHub organization.
1. Open the `header.md` file (`https://github.com/<my_org>/helix-example-2/edit/master/header.md`), add or change some links in the list, then click _Commit changes_.
1. Open the `footer.md` file (`https://github.com/<my_org>/helix-example-2/edit/master/footer.md`), change the content to your liking, then click _Commit changes_.
1. Go to `https://helix-example-2--<my_org>.project-helix.page/` and observe your custom header and footer.

> Alternatively, you could also reuse your existing GitHub repository and add `header.md` and `footer.md` files there.

## Add your HTML files

> You'll need:
> - basic HTML knowledge

If you have existing HTML files or snippets that you wish to reuse, you can simply add them to your GitHub repository and have _Helix_Pages_ deliver them for you:

1. Go to [Helix Example 3](https://github.com/adobe/helix-example-3), click _Fork_ and confirm your GitHub org1nization.
1. Open the `index.html` file (`https://github.com/<my_org>/helix-example-3/edit/master/index.html`), replace its contents with your own HTML, then click _Commit changes_.
1. Do the same with `header.html` and `footer.html` if you like.
1. Go to `https://helix-example-3--<my_org>.project-helix.page/` to see the result.

> Alternatively, you could also reuse your existing GitHub repository and add HTML files there.

## Add your own styling

> You'll need:
> - basic CSS knowledge

_Helix Pages_ allows you to override the default CSS and add custom images. You can either do this directly in GitHub, or on your local computer.

### GitHub

1. Go to [Helix Example 4](https://github.com/adobe/helix-example-4), click _Fork_ and confirm your GitHub organization.
1. Go to `https://helix-example-4--<my_org>.project-helix.page/` and view the default page styling.
1. Open the `style.css` file (`https://github.com/<my_org>/helix-example-4/edit/master/style.css`), edit the CSS, then click _Commit changes_. 
1. Refresh `https://helix-example-4--<my_org>.project-helix.page/` and observe your style changes. 
1. Repeat steps 3 and 4 until you are happy with how your page looks.

### Local development

For an improved developer experience, using a local environment and the IDE of your choice, we proudly introduce you to the _Helix CLI_ (`hlx`).

> You'll need:
> - a shell :)
> - [Node.js and `npm`](https://nodejs.org/en/)
> - [Helix CLI](https://www.npmjs.com/package/@adobe/helix-cli)
> - [Git](https://git-scm.com/)

1. Open your favorite shell.
1. Type `hlx demo --type=4 helix-example-4`<br>
This will fork https://github.com/adobe/helix-example-4 and clone it locally into a folder called `helix-example-4`.
1. Type `cd helix-example-4` 
1. Type `hlx up`<br>
This will build a _Helix Pages_ project locally, start a web server and open a new browser window at `http://localhost:3000` when done.
1. View the page localy rendered by _Helix Pages_ using the default page styling.
1. Open the `style.css` file in the IDE of your choice, edit the CSS, then save it. 
1. Reload the browser window to observe your style changes.
1. Repeat steps 6 and 7 until you are happy with how your page looks.
1. Type `git add style.css && git commit -m"custom styling" && git push`<br>
This will commit your local style changes to the GitHub repository.
1. Go to `https://helix-example-4--<my_org>.project-helix.page/` and admire your published page with custom styling. 

> Alternatively, you could also reuse your existing GitHub repository and add a `style.css` file there.

> Note: You can also add images to your repository and reference them from within your CSS.

## Add your own domain

> You'll need
> - A [Fastly](https://github.com/adobe/project-helix/blob/master/SERVICES.md#fastly) account?

Coming soon...

## Add your own code

> You'll need:
> - basic JavaScript knowledge
> - basic HTML knowledge

_Helix Pages_ allows you to control the rendering of your content by changing the HTL template and adding custom JavaScript code.

1. Open your favorite shell.
2. Type `hlx demo --type=6 helix-example-6`<br>
This will fork https://github.com/adobe/helix-example-6 and clone it locally into a folder called `helix-example-6`.
3. Type `cd helix-example-6`
4. Type `hlx up`<br>
This will build a _Helix Pages_ project locally, start a web server and open a new browser window at `http://localhost:3000` when done.
5. View the page localy rendered by _Helix Pages_ using the default rendering.
6. Open the `src/html.pre.js` file in the IDE of your choice. 
7. Inside the `pre` function, add<br>
```js
context.content.time = `${new Date()}`;
```
8. Save the changes.
6. Open the `src/html.htl` file in your IDE.
7. Right after the `<body>` tag, add:
```html
<div>
  <em>Helix Pages generated this page on ${content.time}</em>
</div>
```
8. Save the changes.
9. Reload the browser window to observe the page rendered using custom code.
11. Type `git add src/* && git commit -m"custom code" && git push`<br>
This will commit your local code changes to the GitHub repository.
12. Go to `https://helix-example-6--<my_org>.project-helix.page/` and observe the page rendered with custom code. 


> Alternatively, you could also reuse your existing GitHub repository, adding a `src` folder containing `html.htl` and `html.pre.js` files.

> Note: You could also do this directly in GitHub, but we recommend writing code in a proper development environment.

## Separate code and content

> You'll need:
> - An [Adobe I/O Runtime](https://github.com/adobe/project-helix/blob/master/SERVICES.md#adobe-io-runtime) account

Coming soon ...

## Proxy a legacy web site

Coming soon ...

## ...
