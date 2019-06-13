# Getting Started with Helix

- Already familiar with GitHub, Markdown, HTML and CSS? You may want to proceed directly to the [first example](http://github.com/adobe/helix-example-first "First example"): This repository shows how to publish content, as well as customize headers, footers and overall styling of your pages.
- If you also feel comfortable with Node.js programming, please continue with the [advanced example](http://github.com/adobe/helix-example-advanced "Advanced example"): This repository explains how to add custom code to take control over the rendering pipeline.
- If you are a bit out of shape or need help understanding some of these tools and concepts, we recommend that you start with the beginner's tutorial below.
- In any case, our [reference documentation](https://www.project-helix.io/ "Reference documentation") provides all the basic concepts and details that you may need.


# Beginner's Tutorial
This guide helps you get started with Helix with very little effort, then progressively discover more features as you need them.


## Step 1: Publish your content (look ma, no hands!)

> You'll need:
> - a [GitHub](https://github.com/join) account
> - basic [Markdown](https://guides.github.com/features/mastering-markdown/) knowledge

The easiest way to publish content is to have [_Helix Pages_](https://www.project-helix.page/) render and deliver a Markdown file in your GitHub repository:

1. Click the _Create new repository_ button to set up a new public [GitHub repository](https://github.com/new/).  
![](./dummy.png "TODO: Create a repository")
1. Click the _Create new file_ button in your GitHub repository to add a new file called `index.md`. This will be your homepage.  
![](./dummy.png "TODO: Create an index.md")
1. Add some great Markdown content. Feel free to use [this example](https://raw.githubusercontent.com/adobe/helix-example-first/master/index.md "example index.md") for starters.
1. Click the _Commit changes_ button.
1. Go to `https://<my-repo>-<my-name>.project-helix.page/` to admire your work. Replace `<my-repo>` and `<my-name>` with your actual repository and username:  
![](./dummy.png "TODO: Helix Pages rendering your content")
1. **Congratulations!** 🎉 You have just published your first page using _Helix Pages_!

> **Note:** If your GitHub username contains one or more dashes, add an extra dash between the repository and username in the _Helix Pages_ URL. For example, if your user is called `hlx-rocks`, and your repository is `test`, the corresponding _Helix Pages_ URL would be `https://test--hlx-rocks.project-helix.page/`.

> **Hint:** You can add as many Markdown files as you like, and reference them using relative links in your Markdown.

> **Hint:** By default, _Helix Pages_ renders the master branch of your GitHub repository, but it could also render any other [branches](https://help.github.com/en/articles/github-glossary#branch "branches explained"). Simply add the branch name at the beginning of the _Helix Pages_ URL and use two dashes between branch, repository and username in the _Helix Pages_ URL: `https://<my-branch>--<my-repo>--<my-name>.project-helix.page/`

## Step 2: Customize header and footer

_Helix Pages_ can render a header and footer based on Markdown content in your GitHub repository:

1. Add a file called `header.md` to your GitHub repository.  
![](./dummy.png "TODO: Create a header.md")
1. Add a logo, and maybe a list of other links you would like to show in the header navigation. Here's some [inspiration](https://raw.githubusercontent.com/adobe/helix-example-first/master/header.md "example header.md").
1. Click the _Commit changes_ button.
1. If you added a logo in step 2, upload that now.  
![](./dummy.png "TODO: Upload logo")
1. Add a file called `footer.md` to your GitHub repository.
1. Add some content you would like to be shown in the footer. Here's a [proposal](https://raw.githubusercontent.com/adobe/helix-example-first/master/footer.md "example footer.md").
1. Click the _Commit changes_ button.
1. Reload `https://<my-repo>-<my-name>.project-helix.page/` to see header and footer in action.  
![](./dummy.png "TODO: Helix Pages rendering your header and footer")

## Step 3: Add your own HTML files

> You'll need:
> - basic HTML knowledge

If you have existing HTML files or snippets that you wish to reuse, you can simply add them to your GitHub repository and have _Helix Pages_ merge and deliver them for you:

1. Add a `about.html` file to your GitHub repository. The file name can be anything, just make sure it has a `.html` extension.
1. Add your HTML. Maybe [like so](https://raw.githubusercontent.com/adobe/helix-example-first/master/htdocs/about.html "example HTML file")?
1. Click _Commit changes_.
1. Refresh `https://<my-repo>-<my-name>.project-helix.page/about.html` to see the rendered result.

> **Note:** If there is a `.md` and `.html` with the same name, _Helix Pages_ will render the HTML file.

> **Hint:** If you add a `header.html` or `footer.html`, _Helix Pages_ will embed them for you.

## Step 4: Add your own styling

> You'll need:
> - basic CSS knowledge

_Helix Pages_ allows you to override the default CSS. You can either do this directly in GitHub, or start developing locally.

### GitHub

1. Add a `style.css` file to your GitHub repository.
1. Insert your CSS rules. See the [default CSS](https://raw.githubusercontent.com/adobe/helix-example-first/master/style.css "default CSS") for pointers where you can add overrides.
1. Click _Commit changes_.
1. Reload `https://<my-repo>-<my-name>.project-helix.page/` to view your style changes in action.
1. Repeat steps 2 - 4 until you are happy with how your page looks.

### Local development

For an improved developer experience, using a local environment and the IDE of your choice, we proudly introduce you to the _Helix CLI_ (`hlx`).

> You'll need:
> - a command line terminal
> - a text editor or IDE
> - a [Git](https://git-scm.com/) client (this guide uses the `git` command line interface, but feel free to use your favorite tool)
> - [Node.js](https://nodejs.org/en/) 8.9 or higher and `npm` or `yarn`
> - [Helix CLI](https://www.npmjs.com/package/@adobe/helix-cli)

1. Open your terminal.
1. Change to the directory where you normally keep your code, e.g. type `cd ~/code`.
1. Clone your GitHub repository into a local directory with the same name. For example,  
Type `git clone https://github.com/<my-name>/<my-repo>.git`
1. Type `cd <my-repo>`
1. Type `hlx up`  
This will build a _Helix Pages_ project locally, start a local web server and open a new browser window at [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") when done.
1. View your page rendered locally by _Helix Pages_ using the default page styling.
1. Create a `style.css` file using your favorite text editor or IDE and insert your CSS rules. See the [default CSS](https://raw.githubusercontent.com/adobe/helix-example-first/master/style.css "Default CSS") for pointers where you can add overrides.
1. Save your changes.
1. Reload [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") to preview your style changes locally.
1. Repeat steps 8 - 10 until you are happy with how your page looks.
1. Persist your local style changes to your GitHub repository. For example,  
Type `git add style.css && git commit -m"custom styling" && git push`
1. Now reload `https://<my-repo>-<my-name>.project-helix.page/` and admire your published page with custom styling.

> **Hint:** You can stop the server using the `ctrl + c` key combination.

> **Hint:** If you use `hlx up` with the `--no-open` argument, it won't open a browser window.

## Step 5: Add your own code

> You'll need:
> - basic JavaScript knowledge
> - basic HTML knowledge

_Helix Pages_ allows you to control the rendering of your content by adding a custom HTL template and JavaScript code. You could do the same directly on GitHub, but since we already set up our local development environment, we'll stick with that:

1. Open your terminal and change to the directory where you checked out your GitHub repository, e.g. type `cd ~/code/<my-repo>`.
2. Type `mkdir src && cd src`  
This will create a `src` directory and change to it.
3. Add an `html.pre.js` file in there using your favorite text editor or IDE. We suggest you start with the [default one](https://raw.githubusercontent.com/adobe/helix-pages/master/src/html.pre.js "default html.pre.js").
4. Inside the `pre` function, add something new to the `context.content` object, e.g.
```js
context.content.time = `${new Date()}`;
```
5. Save your changes.
6. Add an `html.htl` in the same folder. Again, you can inspire from [the default](https://raw.githubusercontent.com/adobe/helix-pages/master/src/html.htl "default html.htl").
7. Display your custom addition., e.g. right after the `<body>` tag, add:
```html
<div>
  <em>Helix Pages generated this page on ${content.time}</em>
</div>
```
8. Save the changes.
9. Reload [`http://localhost:3000/`](http://localhost:3000/ "your local Helix server") to see the page rendered locally using your custom code.
10. Persist your local style changes to your GitHub repository. For example,  
Type `git add src/* && git commit -m"custom code" && git push`
11. Go to `https://<my-repo>-<my-name>.project-helix.page/` and observe the published page being rendered with your custom code:  
![](./dummy.png "TODO: Helix Pages rendering your content with custom code")

## Step 6: Add your own domain

Coming soon...

## Step 7: Separate code and content

> You'll need:
> - An [Adobe I/O Runtime](https://github.com/adobe/project-helix/blob/master/SERVICES.md#adobe-io-runtime) account
> - A [Fastly](https://github.com/adobe/project-helix/blob/master/SERVICES.md#fastly) account

Coming soon ...

## Step 8: Proxy your legacy web site

Coming soon ...

## Step 9: ...
