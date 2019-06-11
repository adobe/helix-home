# Getting Started with Helix

This guide helps you as a web developer get started with Helix with very little effort, then progressively discover more features as you need them.

## Step 1: Publish your content (look ma, no hands!)

> You'll need:
> - a [GitHub](https://github.com/join) account
> - basic [Markdown](https://guides.github.com/features/mastering-markdown/) knowledge

The easiest way to publish content is to have [_Helix Pages_](https://www.project-helix.page/) render and deliver a Markdown file in your GitHub repository:

1. Click the _Create new repository_ button to set up a new public [GitHub repository](https://github.com/new/).
   <br>![](./dummy.png "TODO: Create a repository")
1. Click the _Create new file_ button in your GitHub repository to add a new file called `index.md`. This will be your homepage.
   <br>![](./dummy.png "TODO: Create an index.md")
1. Add some great Markdown content. Feel free to use [this example](https://raw.githubusercontent.com/adobe/helix-example/master/index.md "example index.md") for starters.
1. Click the _Commit changes_ button.
1. Go to `https://<my-repo>-<my_org>.project-helix.page/` to admire your work. Replace `<my-repo>` and `<my-org>` with your actual repository and organization names:
   <br>![](./dummy.png "TODO: Helix Pages rendering your content")
1. **Congratulations!** 🎉 You have just published your first page using _Helix Pages_!

> **Hint:** You can add as many Markdown files as you like, and link them in Markdown.

> **Note:** If your GitHub organization contains one or more dashes, add an extra dash between the repository and organization name in the _Helix Pages_ URL. For example, if your organization is called `my-org`, and your repository is `test`, the corresponding _Helix Pages_ URL would be `https://test--my-org.project-helix.page/`.

## Step 2: Customize header and footer

_Helix Pages_ can render a header and footer based on Markdown content in your GitHub repository:

1. Add a file called `header.md` to your GitHub repository.
   <br>![](./dummy.png "TODO: Create a header.md")
1. Add a logo, and maybe a list of other links you would like to show in the header navigation. Here's some [inspiration](https://raw.githubusercontent.com/adobe/helix-example/master/header.md "example header.md").
1. Click the _Commit changes_ button.
1. If you added a logo in step 2, upload that now.
   <br>![](./dummy.png "TODO: Upload logo")
1. Add a file called `footer.md` to your GitHub repository.
1. Add some content you would like to be shown in the footer. Here's a [proposal](https://raw.githubusercontent.com/adobe/helix-example/master/footer.md "example footer.md").
1. Click the _Commit changes_ button.
1. Reload `https://<my-repo>-<my_org>.project-helix.page/` to see header and footer in action.
   <br>![TODO: Helix Pages rendering your header and footer](./dummy.png)

## Step 3: Add your own HTML files

> You'll need:
> - basic HTML knowledge

If you have existing HTML files or snippets that you wish to reuse, you can simply add them to your GitHub repository and have _Helix_Pages_ merge and deliver them for you:

1. Add a `static.html` file to your GitHub repository. The file name can be anything, just make sure it has a `.html` extension.
1. Add your HTML. Maybe [like so](https://raw.githubusercontent.com/adobe/helix-example/master/htdocs/static.html "example HTML file")?
1. Click _Commit changes_.
1. Refresh `https://<my-repo>-<my_org>.project-helix.page/static.html` to see the rendered result.

> **Note:** If there is a `.md` and `.html` with the same name, _Helix Pages_ will give precendence to the Markdown file. Rename or remove the Markdown file to have the HTML file rendered instead.

> **Hint:** If you add a static `header.html` or `footer.html` instead of their Markdown versions, _Helix Pages_ will embed these for you.

## Add your own styling

> You'll need:
> - basic CSS knowledge

_Helix Pages_ allows you to override the default CSS. You can either do this directly in GitHub, or start developing locally.

### GitHub

1. Add a `style.css` file to your GitHub repository.
1. Insert your CSS rules. See the [default CSS](https://raw.githubusercontent.com/adobe/helix-pages/master/htdocs/style.css "default CSS") for pointers where you can add overrides.
1. Click _Commit changes_.
1. Reload `https://<my-repo>-<my_org>.project-helix.page/` to view your style changes in action.
1. Repeat steps 2 - 4 until you are happy with how your page looks.

### Local development

For an improved developer experience, using a local environment and the IDE of your choice, we proudly introduce you to the _Helix CLI_ (`hlx`). For the sake of simplicity, we just assume you are using _Visual Studio Code_ and have a `code` alias for it.

> You'll need:
> - a shell :)
> - an IDE, e.g. [Visual Studio Code](https://code.visualstudio.com/)
> - [Node.js](https://nodejs.org/en/) 8.9 or higher and `npm`
> - [Helix CLI](https://www.npmjs.com/package/@adobe/helix-cli)
> - [Git](https://git-scm.com/)

1. Open your favorite shell.
1. Change to the directory where you normally keep your code, e.g. type `cd ~/code`.
1. Type `git clone https://github.com/<my-org>/<my-repo>.git`
   <br>This will your GitHub repository locally into a folder with the same name as your GitHub repository.
1. Type `cd <my_repo>`
1. Type `hlx up &`
   <br>This will build a _Helix Pages_ project locally, start a local web server in the background and open a new browser window at [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") when done.
1. View your page rendered locally by _Helix Pages_ using the default page styling.
1. Type `code .` to open this project in the IDE
1. Create a `style.css` file and insert your CSS rules. See the [default CSS](https://raw.githubusercontent.com/adobe/helix-pages/master/htdocs/style.css "Default CSS") for pointers where you can add overrides.
1. Save your changes.
1. Reload [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") to preview your style changes locally.
1. Repeat steps 8 - 10 until you are happy with how your page looks.
1. Type `git add style.css && git commit -m"custom styling" && git push`
   <br>This will persist your local style changes to the GitHub repository.
1. Now reload `https://<my_repo>-<my_org>.project-helix.page/` and admire your published page with custom styling. 

> **Hint:** If you use `hlx up` with the `--no-open` argument, it won't open a browser window.

## Add your own code

> You'll need:
> - basic JavaScript knowledge
> - basic HTML knowledge

_Helix Pages_ allows you to control the rendering of your content by changing the HTL template and adding custom JavaScript code.

1. Open your favorite shell and change to the directory where you checked out your GitHub repository, e.g. type `cd ~/code/<my-repo>`.
2. Type `code .` to open this project in the IDE.
3. Create a `src` folder.
4. Add an `html.pre.js` file in there. For example, start with the [default one](https://raw.githubusercontent.com/adobe/helix-pages/master/src/html.pre.js "default html.pre.js").
5. Inside the `pre` function, add something to the `content` object, e.g.
```js
context.content.time = `${new Date()}`;
```
6. Save your changes.
7. Add an `html.htl` in the same folder. Again, you can inspire from [the default](https://raw.githubusercontent.com/adobe/helix-pages/master/src/html.htl "default html.htl").
8. Display your custom addition., e.g. right after the `<body>` tag, add:
```html
<div>
  <em>Helix Pages generated this page on ${content.time}</em>
</div>
```
9. Save the changes.
10. Reload [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") to see the page rendered locally using your custom code.
11. Type `git add src/* && git commit -m"custom code" && git push`
    <br>This will commit your local code changes to the GitHub repository.
12. Go to `https://<my-repo>-<my_org>.project-helix.page/` and observe the published page being rendered with your custom code. 


> **Note:** You could also add custom code directly in GitHub, but we recommend against writing code outside a proper development environment.

## Add your own domain

> You'll need
> - A [Fastly](https://github.com/adobe/project-helix/blob/master/SERVICES.md#fastly) account

Coming soon...

## Separate code and content

> You'll need:
> - An [Adobe I/O Runtime](https://github.com/adobe/project-helix/blob/master/SERVICES.md#adobe-io-runtime) account

Coming soon ...

## Proxy your legacy web site

Coming soon ...

## ...
